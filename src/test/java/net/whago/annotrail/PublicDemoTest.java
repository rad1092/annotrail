package net.whago.annotrail;

import java.nio.file.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PublicDemoTest {
    @TempDir Path temp;

    @Test void downloadableSamplesFollowTheDocumentedReview() throws Exception {
        Path sample = Path.of("examples/review-demo");
        Path old = sample.resolve("old.pdf"), revised = sample.resolve("new.pdf");
        byte[] oldBytes = Files.readAllBytes(old), newBytes = Files.readAllBytes(revised);
        Path plan = temp.resolve("plan.json"), output = temp.resolve("reviewed.pdf"), report = temp.resolve("report.json");
        assertEquals(1, Main.run(new String[]{"analyze", "--old", old.toString(), "--new", revised.toString(), "--plan", plan.toString()}));
        String[] command = {"export", "--old", old.toString(), "--new", revised.toString(), "--plan", plan.toString(),
            "--choices", sample.resolve("choices.json").toString(), "--output", output.toString(), "--report", report.toString()};
        assertEquals(0, Main.run(command));
        var result = JsonParser.parseString(Files.readString(report)).getAsJsonObject().getAsJsonObject("result");
        assertEquals(1, result.get("transferred").getAsInt());
        assertEquals(3, result.get("skipped").getAsInt());
        byte[] exported = Files.readAllBytes(output), evidence = Files.readAllBytes(report);
        assertEquals(2, Main.run(command));
        assertArrayEquals(exported, Files.readAllBytes(output));
        assertArrayEquals(evidence, Files.readAllBytes(report));
        assertArrayEquals(oldBytes, Files.readAllBytes(old));
        assertArrayEquals(newBytes, Files.readAllBytes(revised));
    }
}
