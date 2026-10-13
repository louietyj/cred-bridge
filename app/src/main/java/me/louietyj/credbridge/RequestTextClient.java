package me.louietyj.credbridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Submits a request-text form the way its page does (see {@code submitViaPage} in
 * claude-skills/request-text/bin/request-text.mjs): GET the page, read its embedded config,
 * seal the fields to the config's key, POST the sealed body to the same URL.
 */
public final class RequestTextClient {

    private static final Pattern CONFIG = Pattern.compile(
            "<script type=\"application/json\" id=\"config\">(.*?)</script>", Pattern.DOTALL);

    /** What a request-text page says about itself. Not a record: see {@link Seal.Sealed}. */
    public static final class Form {
        private final String id, key, title;
        private final List<String> fieldNames;

        Form(String id, String key, String title, List<String> fieldNames) {
            this.id = id;
            this.key = key;
            this.title = title;
            this.fieldNames = List.copyOf(fieldNames);
        }

        public String id() { return id; }
        public String key() { return key; }
        public String title() { return title; }
        public List<String> fieldNames() { return fieldNames; }
    }

    private RequestTextClient() {}

    /** Only https tunnels under {@code hostSuffix}, plus 127.0.0.1 when {@code allowLocal}. */
    public static boolean isAllowedUrl(String url, String hostSuffix, boolean allowLocal) {
        try {
            URI u = new URI(url);
            String host = u.getHost();
            if (host == null) return false;
            if (allowLocal && "http".equals(u.getScheme()) && "127.0.0.1".equals(host)) return true;
            return "https".equals(u.getScheme()) && host.endsWith(hostSuffix);
        } catch (Exception e) {
            return false;
        }
    }

    public static Form fetchForm(String url) throws IOException {
        String html = Http.send("GET", url, null, null);
        Matcher m = CONFIG.matcher(html);
        if (!m.find()) throw new IOException("the request page has no config");
        try {
            JSONObject cfg = new JSONObject(m.group(1));
            JSONArray fields = cfg.getJSONArray("fields");
            List<String> names = new ArrayList<>();
            for (int i = 0; i < fields.length(); i++) names.add(fields.getJSONObject(i).getString("name"));
            return new Form(cfg.getString("id"), cfg.getString("key"), cfg.optString("title"), names);
        } catch (JSONException e) {
            throw new IOException("the request page's config is malformed", e);
        }
    }

    /** Seals {@code fields} to {@code form}'s key and POSTs it. Field names must match the form's. */
    public static void submit(String url, Form form, Map<String, String> fields)
            throws IOException, GeneralSecurityException {
        if (!fields.keySet().equals(new HashSet<>(form.fieldNames()))) {
            throw new IOException("this request asks for " + form.fieldNames() + ", not " + fields.keySet());
        }
        Seal.Sealed s = Seal.seal(form.key(), form.id(), new JSONObject(fields).toString());
        String body;
        try {
            body = new JSONObject().put("epk", s.epk()).put("iv", s.iv()).put("ct", s.ct()).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        Http.send("POST", url, null, body);
    }
}
