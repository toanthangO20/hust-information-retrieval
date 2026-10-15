package vn.hust.ir.extract;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.bson.Document;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.ValidationOptions;

/**
 * P6 — Nạp pages.final.jsonl / links.final.jsonl vào MongoDB với lược đồ có kiểm tra ($jsonSchema) và chỉ mục.
 * Lược đồ chi tiết: docs/SCHEMA.md.
 */
public final class MongoLoader {
    private MongoLoader() {}

    // [DEMO:mongo-schema] $jsonSchema của pages / links
    private static final String PAGES_SCHEMA = "{ $jsonSchema: { bsonType: 'object', required: ['_id','url','host','kind','inDataset'],"
            + " properties: {"
            + " _id: {bsonType:'string'}, url: {bsonType:'string'}, host: {bsonType:'string'},"
            + " kind: {enum:['html','document','image','external']},"
            + " docType: {bsonType:'string'}, inDataset: {bsonType:'bool'},"
            + " title: {bsonType:'object', required:['value','source','conf'], properties:{value:{bsonType:'string'},source:{bsonType:'string'},conf:{bsonType:'double'}}},"
            + " publishedAt: {bsonType:'object', required:['value','source','conf']},"
            + " publishedDate: {bsonType:'date'},"
            + " author: {bsonType:'object', required:['value','source','conf']},"
            + " content: {bsonType:'object', properties:{text:{bsonType:'string'}, words:{bsonType:'int'}, algo:{bsonType:'string'}}},"
            + " inlinks: {bsonType:'int', minimum:0}, inlinksContent: {bsonType:'int', minimum:0},"
            + " anchors: {bsonType:'array', items:{bsonType:'object', required:['text','count']}}"
            + " } } }";
    private static final String LINKS_SCHEMA = "{ $jsonSchema: { bsonType: 'object', required: ['src','dst','kind','inContent'],"
            + " properties: { src:{bsonType:'string'}, dst:{bsonType:'string'}, kind:{enum:['a','img','iframe','embed']},"
            + " anchor:{bsonType:'string'}, inContent:{bsonType:'bool'}, count:{bsonType:'int',minimum:1} } } }";

    public static void load(Path dir, String uri, String dbName, boolean drop) throws Exception {
        try (MongoClient client = MongoClients.create(uri)) {
            MongoDatabase db = client.getDatabase(dbName);
            db.runCommand(new Document("ping", 1));
            if (drop) {
                db.getCollection("pages").drop();
                db.getCollection("links").drop();
                db.getCollection("meta").drop();
            }
            db.createCollection("pages", new CreateCollectionOptions().validationOptions(
                    new ValidationOptions().validator(Document.parse(PAGES_SCHEMA)).validationLevel(
                            com.mongodb.client.model.ValidationLevel.MODERATE)));
            db.createCollection("links", new CreateCollectionOptions().validationOptions(
                    new ValidationOptions().validator(Document.parse(LINKS_SCHEMA)).validationLevel(
                            com.mongodb.client.model.ValidationLevel.MODERATE)));
            MongoCollection<Document> pages = db.getCollection("pages");
            MongoCollection<Document> links = db.getCollection("links");
            long np = insert(pages, dir.resolve("pages.final.jsonl"), true);
            long nl = insert(links, dir.resolve("links.final.jsonl"), false);

            // [DEMO:mongo-indexes] chỉ mục
            pages.createIndex(Indexes.ascending("url"), new IndexOptions().unique(true));
            pages.createIndex(Indexes.ascending("host", "kind"));
            pages.createIndex(Indexes.descending("pagerankContent"));
            pages.createIndex(Indexes.descending("publishedDate"));
            pages.createIndex(Indexes.text("title.value"));
            links.createIndex(Indexes.ascending("dst", "inContent"));
            links.createIndex(Indexes.ascending("src"));
            links.createIndex(Indexes.ascending("srcHost", "dstHost"));

            Document meta = new Document("_id", "extract")
                    .append("loadedAt", new Date()).append("pages", np).append("links", nl)
                    .append("source", dir.toString());
            Path st = dir.resolve("graph_stats.json");
            if (Files.exists(st)) meta.append("graphStats", Document.parse(Files.readString(st, StandardCharsets.UTF_8)));
            db.getCollection("meta").insertOne(meta);
            System.out.println("Đã nạp: pages=" + np + ", links=" + nl + " vào " + dbName);
        }
    }

    private static long insert(MongoCollection<Document> col, Path file, boolean pages) throws Exception {
        long n = 0;
        List<Document> batch = new ArrayList<>(1000);
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.isBlank()) continue;
                Document d = Document.parse(l);
                if (pages) {
                    Object pa = d.get("publishedAt");
                    if (pa instanceof Document) {
                        String v = ((Document) pa).getString("value");
                        try {
                            d.append("publishedDate", Date.from(LocalDate.parse(v).atStartOfDay().toInstant(ZoneOffset.UTC)));
                        } catch (Exception ignored) { }
                    }
                    Object fa = d.get("fetchedAt");
                    if (fa instanceof Number) d.put("fetchedAt", new Date(((Number) fa).longValue()));
                }
                batch.add(d);
                if (batch.size() >= 1000) {
                    col.insertMany(batch, new InsertManyOptions().ordered(false));
                    n += batch.size();
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            col.insertMany(batch, new InsertManyOptions().ordered(false));
            n += batch.size();
        }
        return n;
    }
}
