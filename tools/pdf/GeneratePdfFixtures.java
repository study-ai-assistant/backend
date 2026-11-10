import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/** Creates only synthetic fixtures, no third-party learning materials. */
public class GeneratePdfFixtures {
    public static void main(String[] args) throws Exception {
        Path destination = Path.of(args[0]);
        Path fontFile = Path.of(args[1]);
        Files.createDirectories(destination);
        try (var document = new PDDocument()) {
            var page = new PDPage(); document.addPage(page);
            var bitmap = new BufferedImage(800, 200, BufferedImage.TYPE_INT_RGB);
            var graphics = bitmap.createGraphics();
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 800, 200);
            graphics.setColor(Color.BLACK); graphics.drawString("Scanned lesson. Image only, no text layer.", 40, 80);
            graphics.dispose();
            var png = new ByteArrayOutputStream(); ImageIO.write(bitmap, "png", png);
            var image = PDImageXObject.createFromByteArray(document, png.toByteArray(), "generated");
            try (var content = new PDPageContentStream(document, page)) { content.drawImage(image, 40, 500, 500, 125); }
            document.save(destination.resolve("scan.pdf").toFile());
        }
        try (var document = new PDDocument()) {
            var page = new PDPage(); document.addPage(page);
            var font = PDType0Font.load(document, fontFile.toFile());
            try (var content = new PDPageContentStream(document, page)) {
                String[][] rows = {{"Planet", "Moons"}, {"Earth", "1"}, {"Mars", "2"}};
                for (int i = 0; i < rows.length; i++) {
                    for (int j = 0; j < 2; j++) {
                        content.beginText(); content.setFont(font, 14); content.newLineAtOffset(60 + j * 180, 720 - i * 35);
                        content.showText(rows[i][j]); content.endText();
                    }
                }
                for (int i = 0; i < 4; i++) { content.moveTo(50, 740 - i * 35); content.lineTo(400, 740 - i * 35); }
                for (int x : new int[] {50, 225, 400}) { content.moveTo(x, 740); content.lineTo(x, 635); }
                content.stroke();
            }
            document.save(destination.resolve("table.pdf").toFile());
        }
        try (var document = new PDDocument()) {
            var page = new PDPage(); document.addPage(page);
            var font = PDType0Font.load(document, fontFile.toFile());
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(font, 16); content.newLineAtOffset(50, 720);
                content.showText("Формулы: E = mc²; a² + b² = c²; α + β = γ."); content.endText();
            }
            document.save(destination.resolve("formulas.pdf").toFile());
        }
        try (var document = Loader.loadPDF(destination.resolve("russian.pdf").toFile())) {
            document.protect(new StandardProtectionPolicy("fixture-owner", "fixture-password", new AccessPermission()));
            document.save(destination.resolve("password.pdf").toFile());
        }
        Files.writeString(destination.resolve("corrupted.pdf"), "%PDF-1.7\nIntentionally corrupted synthetic fixture.\n");
    }
}
