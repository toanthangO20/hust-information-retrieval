package vn.hust.ir.extract;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.descending;

import org.bson.Document;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/** Minh hoạ nhanh dữ liệu đã nạp trong MongoDB (dùng khi báo cáo): thống kê, PageRank cao nhất, nguồn giới thiệu của ảnh. */
public final class MongoDemo {
    private MongoDemo() {}

    public static void run(String uri, String dbName) {
        try (MongoClient c = MongoClients.create(uri)) {
            MongoDatabase db = c.getDatabase(dbName);
            System.out.println("pages: " + db.getCollection("pages").countDocuments()
                    + " | trong bộ dữ liệu: " + db.getCollection("pages").countDocuments(eq("inDataset", true)));
            for (String k : new String[] {"html", "document", "image", "external"})
                System.out.println("  kind=" + k + ": " + db.getCollection("pages").countDocuments(eq("kind", k)));
            System.out.println("links: " + db.getCollection("links").countDocuments()
                    + " | nằm trong nội dung chính: " + db.getCollection("links").countDocuments(eq("inContent", true)));

            System.out.println("\n== 5 trang PageRank (liên kết trong nội dung) cao nhất ==");
            for (Document d : db.getCollection("pages").find(eq("inDataset", true)).sort(descending("pagerankContent")).limit(5)) {
                Document t = d.get("title", Document.class);
                System.out.printf("%.2e  in=%d  %s%n   %s%n", d.getDouble("pagerankContent"), d.getInteger("inlinks", 0),
                        t == null ? "(không tiêu đề)" : t.getString("value"), d.getString("url"));
            }

            System.out.println("\n== Một ảnh và các trang giới thiệu nó (nguồn giới thiệu + văn bản mô tả) ==");
            Document img = db.getCollection("pages").find(and(eq("kind", "image"), new Document("inlinks", new Document("$gte", 3).append("$lte", 40)), new Document("altTexts.0", new Document("$exists", true))))
                    .sort(descending("inlinks")).first();
            if (img != null) {
                System.out.println(img.getString("url") + "  (inlinks=" + img.getInteger("inlinks") + ")");
                System.out.println("  văn bản liên kết tổng hợp: " + img.get("anchors") + " | alt: " + img.get("altTexts"));
                for (Document l : db.getCollection("links").find(eq("dst", img.getString("_id"))).limit(3))
                    System.out.printf("  <- %s%n     anchor/alt: %s | %s%n", l.getString("srcUrl"), l.getString("anchor"), l.getString("alt"));
            }
        }
    }
}
