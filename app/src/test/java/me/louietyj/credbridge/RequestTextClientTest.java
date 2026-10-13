package me.louietyj.credbridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RequestTextClientTest {

    // request-text from a sibling claude-skills checkout, or wherever REQUEST_TEXT_MJS points.
    private static final File MJS = new File(System.getenv().getOrDefault("REQUEST_TEXT_MJS",
            "../../claude-skills/request-text/bin/request-text.mjs"));
    private static File home;

    @BeforeClass
    public static void makeHome() throws IOException {
        home = Files.createTempDirectory("request-text-test").toFile();
    }

    @AfterClass
    public static void closeAll() throws Exception {
        if (MJS.exists()) node("close", "--all");
    }

    @Test
    public void hkdfMatchesRfc5869TestCase1() throws Exception {
        HexFormat hex = HexFormat.of();
        byte[] okm = Seal.hkdfSha256(
                hex.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
                hex.parseHex("000102030405060708090a0b0c"),
                hex.parseHex("f0f1f2f3f4f5f6f7f8f9"), 42);
        assertArrayEquals(hex.parseHex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db0"
                + "2d56ecc4c5bf34007208d5b887185865"), okm);
    }

    @Test
    public void onlyTunnelUrlsAreAllowed() {
        String sfx = ".hostc.app";
        assertTrue(RequestTextClient.isAllowedUrl("https://abc123.hostc.app/tok", sfx, false));
        assertFalse(RequestTextClient.isAllowedUrl("http://abc123.hostc.app/tok", sfx, false));
        assertFalse(RequestTextClient.isAllowedUrl("https://evil.example/tok", sfx, false));
        assertFalse(RequestTextClient.isAllowedUrl("https://hostc.app.evil.example/tok", sfx, false));
        assertFalse(RequestTextClient.isAllowedUrl("http://127.0.0.1:8080/tok", sfx, false));
        assertTrue(RequestTextClient.isAllowedUrl("http://127.0.0.1:8080/tok", sfx, true));
        assertFalse(RequestTextClient.isAllowedUrl("not a url", sfx, true));
    }

    @Test
    public void realRequestTextServerDecryptsWhatWeSubmit() throws Exception {
        assumeTrue("request-text not found at " + MJS, MJS.exists());
        String[] opened = open();
        String id = opened[0], url = opened[1];

        RequestTextClient.Form form = RequestTextClient.fetchForm(url);
        assertEquals(id, form.id());
        assertEquals("example.com", form.title());
        assertEquals(List.of("username", "password"), form.fieldNames());

        Map<String, String> sent = Map.of("username", "louie@example.com",
                "password", "pä$$ wörd \"q\" \\ 🔑");
        RequestTextClient.submit(url, form, sent);

        JSONObject got = new JSONObject(node("wait", id, "--timeout", "10"));
        assertEquals(sent.get("username"), got.getString("username"));
        assertEquals(sent.get("password"), got.getString("password"));
        assertEquals(2, got.length());

        // The link is single-use.
        Http.HttpError again = assertThrows(Http.HttpError.class,
                () -> RequestTextClient.submit(url, form, sent));
        assertEquals(410, again.status);
    }

    @Test
    public void fieldsMustMatchTheForm() throws Exception {
        assumeTrue("request-text not found at " + MJS, MJS.exists());
        String url = open()[1];
        RequestTextClient.Form form = RequestTextClient.fetchForm(url);
        assertThrows(IOException.class,
                () -> RequestTextClient.submit(url, form, Map.of("password", "x")));
    }

    private static String[] open() throws Exception {
        String out = node("open", "--local", "--title", "example.com", "--ttl-min", "2",
                "--field", "username:line", "--field", "password:secret");
        Matcher id = Pattern.compile("^id:\\s+(\\S+)", Pattern.MULTILINE).matcher(out);
        Matcher url = Pattern.compile("^url:\\s+(\\S+)", Pattern.MULTILINE).matcher(out);
        assertTrue(out, id.find() && url.find());
        return new String[] {id.group(1), url.group(1)};
    }

    private static String node(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("node", MJS.getPath()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("REQUEST_TEXT_HOME", home.getPath());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) throw new IOException("request-text " + args[0] + " exited " + code + ":\n" + out);
        return out;
    }
}
