package vn.hust.ir.eval;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/** multilingual-e5-small (ONNX int8): nhúng chuỗi thành vec-tơ 384 chiều, mean-pooling theo mask rồi chuẩn hóa L2. */
public final class E5Encoder implements Closeable {
    public static final int DIM = 384;
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final E5Tokenizer tokenizer;
    private final Set<String> inputNames;

    public E5Encoder(Path modelDir, int threads) throws Exception {
        tokenizer = new E5Tokenizer(modelDir.resolve("tokenizer.json"));
        OrtSession.SessionOptions so = new OrtSession.SessionOptions();
        so.setIntraOpNumThreads(threads);
        session = env.createSession(modelDir.resolve("model_int8.onnx").toString(), so);
        inputNames = session.getInputNames();
    }

    public E5Tokenizer tokenizer() {
        return tokenizer;
    }

    /** Nhúng một lô chuỗi đã mã hóa (mỗi phần tử là mảng id, độ dài tùy ý). */
    public float[][] embed(int[][] batch) throws Exception {
        int b = batch.length, len = 0;
        for (int[] ids : batch) len = Math.max(len, ids.length);
        long[][] input = new long[b][len], mask = new long[b][len], type = new long[b][len];
        for (int i = 0; i < b; i++) {
            java.util.Arrays.fill(input[i], E5Tokenizer.PAD);
            for (int j = 0; j < batch[i].length; j++) {
                input[i][j] = batch[i][j];
                mask[i][j] = 1;
            }
        }
        Map<String, OnnxTensor> feed = new HashMap<>();
        try {
            if (inputNames.contains("input_ids")) feed.put("input_ids", OnnxTensor.createTensor(env, input));
            if (inputNames.contains("attention_mask")) feed.put("attention_mask", OnnxTensor.createTensor(env, mask));
            if (inputNames.contains("token_type_ids")) feed.put("token_type_ids", OnnxTensor.createTensor(env, type));
            try (OrtSession.Result r = session.run(feed)) {
                float[][][] h = (float[][][]) r.get(0).getValue();
                float[][] out = new float[b][DIM];
                for (int i = 0; i < b; i++) {
                    double cnt = 0;
                    for (int j = 0; j < batch[i].length; j++) {
                        cnt++;
                        for (int d = 0; d < DIM; d++) out[i][d] += h[i][j][d];
                    }
                    double norm = 0;
                    for (int d = 0; d < DIM; d++) {
                        out[i][d] /= cnt;
                        norm += out[i][d] * out[i][d];
                    }
                    norm = Math.sqrt(norm);
                    for (int d = 0; d < DIM; d++) out[i][d] /= norm;
                }
                return out;
            }
        } finally {
            for (OnnxTensor t : feed.values()) t.close();
        }
    }

    public float[] embedQuery(String q) throws Exception {
        return embed(new int[][] {tokenizer.encode("query: " + q, 64)})[0];
    }

    @Override
    public void close() throws java.io.IOException {
        try {
            session.close();
        } catch (Exception ignored) { }
    }
}
