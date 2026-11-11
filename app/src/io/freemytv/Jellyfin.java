package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Recently Added, from your Jellyfin server (jellyfin_server in config.properties, its key sent to each TV by
 * tools/send_key.py into Secrets, never in the APK): the newest items of the key's user (or of jellyfin_user, for an
 * API key made in the dashboard), read on their own thread when the home screen comes back, at most every few minutes.
 * OK opens the item in Jellyfin's Android TV app, and Continue Watching's Jellyfin cards get the episode's own picture
 * from here (episodePictures). A TV without the key keeps the one row of Continue Watching.
 */
final class Jellyfin {
    private Jellyfin() {}

    static final String PKG = "org.jellyfin.androidtv";
    /** Jellyfin's Android TV app opens an item from its start screen with this extra (as its home screen rows do). */
    private static final String START = PKG + "/.ui.startup.StartupActivity";
    static final int MAX = 16;
    static final int LOADING = 0, OK = 1, FAILED = 2;
    private static final long FRESH_MS = 3 * 60_000L, RETRY_MS = 60_000L;
    private static final int MAX_JSON = 4 << 20, MAX_PICTURE = 8 << 20;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile String key = "";
    private static volatile long keyAt = -1;
    private static volatile String userId;
    private static volatile List<WatchNext.Item> items = Collections.emptyList();
    private static volatile int state = LOADING;
    private static volatile long readAt = -1;
    private static volatile boolean reading;

    /** A server in config.properties and its key sent to this TV (the key read again every 30 s at most). */
    static boolean configured(Context c) {
        if (Config.JELLYFIN_SERVER.trim().isEmpty()) return false;
        long now = SystemClock.uptimeMillis();
        if (keyAt < 0 || now - keyAt > 30_000) {
            String k = Secrets.get(c, Secrets.JELLYFIN_KEY);
            if (!k.equals(key)) {
                userId = null;   // another key may be another user's
                readAt = -1;
            }
            key = k;
            keyAt = now;
        }
        return !key.isEmpty();
    }

    /** LOADING (not read yet), OK or FAILED (the last read; the list keeps what came before). */
    static int state() {
        return state;
    }

    /**
     * The newest items as they are now (off the main thread: the key may be read). When they're older than a few
     * minutes they're read again on Jellyfin's own thread, and `changed` runs on the main thread if they changed.
     */
    static List<WatchNext.Item> latest(Context c, Runnable changed) {
        if (!configured(c)) return Collections.emptyList();
        long now = SystemClock.uptimeMillis();
        long due = state == FAILED ? RETRY_MS : FRESH_MS;
        if (!reading && (readAt < 0 || now - readAt > due)) {
            reading = true;
            readAt = now;
            WORKER.execute(() -> {
                int before = state;
                List<WatchNext.Item> old = items;
                try {
                    items = read();
                    state = OK;
                } catch (Exception e) {   // never past this thread: :home's watchdog counts it as a crash
                    AppLog.w("jellyfin: " + e.getMessage());
                    state = FAILED;
                } finally {
                    readAt = SystemClock.uptimeMillis();
                    reading = false;
                }
                if (state != before || items != old) MAIN.post(changed);
            });
        }
        return items;
    }

    private static List<WatchNext.Item> read() throws IOException, org.json.JSONException {
        String user = user();
        String query = "?userId=" + user + "&limit=" + MAX + "&groupItems=true&enableImageTypes=Primary,Backdrop,Thumb"
                + "&imageTypeLimit=1&fields=ParentId";
        String body;
        try {
            body = get("/Items/Latest" + query);
        } catch (Status e) {
            if (e.code != 404) throw e;
            body = get("/Users/" + user + "/Items/Latest" + query);   // servers before 10.9
        }
        JSONArray all = new JSONArray(body);
        List<WatchNext.Item> out = new ArrayList<>();
        for (int i = 0; i < all.length() && out.size() < MAX; i++) {
            JSONObject o = all.getJSONObject(i);
            String id = o.optString("Id", "");
            if (!id.matches("[0-9a-fA-F-]{32,36}")) continue;
            WatchNext.Item it = new WatchNext.Item(PKG, picture(o), true);
            String type = o.optString("Type");
            String series = o.optString("SeriesName", "");
            it.title = "Episode".equals(type) && !series.isEmpty() ? series : o.optString("Name", "");
            if (it.title.isEmpty()) continue;
            if ("Episode".equals(type) && o.has("ParentIndexNumber") && o.has("IndexNumber")) {
                it.episode = Ui.trf("S{season} E{episode}", "season", o.optInt("ParentIndexNumber"),
                        "episode", o.optInt("IndexNumber"));
            } else if (o.optInt("ProductionYear") > 0) {
                it.episode = String.valueOf(o.optInt("ProductionYear"));
            }
            it.app = "Jellyfin";
            it.intent = "intent:#Intent;component=" + START + ";S.ItemId=" + id + ";end";
            out.add(it);
        }
        return out;
    }

    /**
     * Continue Watching's cards from Jellyfin's app with the episode's own picture: the app publishes the show's art,
     * and its intent names the item (S.ItemId). Ids not seen yet are looked up together on Jellyfin's thread, and
     * `changed` runs on the main thread when their pictures are known; until then a card keeps the app's picture.
     */
    static List<WatchNext.Item> episodePictures(Context c, List<WatchNext.Item> list, Runnable changed) {
        if (!configured(c)) return list;
        List<WatchNext.Item> out = new ArrayList<>(list.size());
        final List<String> unknown = new ArrayList<>();
        for (WatchNext.Item it : list) {
            String id = PKG.equals(it.pkg) ? itemId(it.intent) : null;
            String path = id == null ? null : PICTURES.get(id);
            if (id != null && path == null && !unknown.contains(id)) unknown.add(id);
            out.add(path == null || path.isEmpty() ? it : it.withJellyfinPicture(path));
        }
        long now = SystemClock.uptimeMillis();
        if (!unknown.isEmpty() && !looking && (lookedAt < 0 || now - lookedAt > RETRY_MS || lookedOk)) {
            looking = true;
            lookedAt = now;
            WORKER.execute(() -> {
                boolean found = false;
                try {
                    found = lookUp(unknown);
                    lookedOk = true;
                } catch (Exception e) {   // never past this thread: :home's watchdog counts it as a crash
                    AppLog.w("jellyfin pictures: " + e.getMessage());
                    lookedOk = false;
                } finally {
                    lookedAt = SystemClock.uptimeMillis();
                    looking = false;
                }
                if (found) MAIN.post(changed);
            });
        }
        return out;
    }

    /** id → its picture's path on the server ("": none), for Continue Watching's cards. */
    private static final java.util.Map<String, String> PICTURES = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean looking, lookedOk;
    private static volatile long lookedAt = -1;

    private static String itemId(String intent) {
        if (intent == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(";S\\.ItemId=([0-9a-fA-F-]{32,36});").matcher(intent);
        return m.find() ? key(m.group(1)) : null;
    }

    /** Ids as the server writes them in lists: 32 hex digits, lower case (an intent may carry the dashed form). */
    private static String key(String id) {
        return id.replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether any picture was found for `ids`; each one asked is remembered, found or not. */
    private static boolean lookUp(List<String> ids) throws IOException, org.json.JSONException {
        String user = user();
        StringBuilder q = new StringBuilder("?userId=").append(user).append("&ids=");
        for (int i = 0; i < ids.size(); i++) q.append(i == 0 ? "" : ",").append(ids.get(i));
        q.append("&enableImageTypes=Primary,Backdrop,Thumb&imageTypeLimit=1&fields=ParentId");
        String body;
        try {
            body = get("/Items" + q);
        } catch (Status e) {
            if (e.code != 404) throw e;
            body = get("/Users/" + user + "/Items" + q);   // servers before 10.9
        }
        JSONArray all = new JSONObject(body).optJSONArray("Items");
        if (PICTURES.size() > 200) PICTURES.clear();
        boolean found = false;
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject o = all.getJSONObject(i);
            String path = picture(o);
            if (path == null) continue;
            PICTURES.put(key(o.optString("Id")), path);
            found = true;
        }
        for (String id : ids) {
            if (!PICTURES.containsKey(id)) PICTURES.put(id, "");
        }
        return found;
    }

    /**
     * A 16:9 picture: an episode's own picture first (its Primary is a still from it), else the item's thumb, a
     * backdrop (its own or its show's), and last its poster.
     */
    private static String picture(JSONObject o) {
        String id = o.optString("Id");
        JSONObject tags = o.optJSONObject("ImageTags");
        boolean episode = "Episode".equals(o.optString("Type"));
        if (episode && tags != null && tags.has("Primary")) return image(id, "Primary", tags.optString("Primary"));
        if (tags != null && tags.has("Thumb")) return image(id, "Thumb", tags.optString("Thumb"));
        String parentThumb = o.optString("ParentThumbItemId", "");
        if (!parentThumb.isEmpty()) return image(parentThumb, "Thumb", o.optString("ParentThumbImageTag"));
        JSONArray backdrops = o.optJSONArray("BackdropImageTags");
        if (backdrops != null && backdrops.length() > 0) return image(id, "Backdrop/0", backdrops.optString(0));
        JSONArray parentBackdrops = o.optJSONArray("ParentBackdropImageTags");
        String parentBackdrop = o.optString("ParentBackdropItemId", "");
        if (!parentBackdrop.isEmpty() && parentBackdrops != null && parentBackdrops.length() > 0) {
            return image(parentBackdrop, "Backdrop/0", parentBackdrops.optString(0));
        }
        if (tags != null && tags.has("Primary")) return image(id, "Primary", tags.optString("Primary"));
        return null;
    }

    private static String image(String id, String type, String tag) {
        if (!id.matches("[0-9a-fA-F-]{32,36}")) return null;
        String path = "/Items/" + id + "/Images/" + type + "?quality=85";
        try {
            if (tag != null && !tag.isEmpty()) path += "&tag=" + URLEncoder.encode(tag, "UTF-8");
        } catch (java.io.UnsupportedEncodingException ignored) {
            // UTF-8 is always there
        }
        return path;
    }

    /** A picture on the server (a path picture() made), at most `width` pixels wide, for WatchArt's thread. */
    static byte[] picture(Context c, String path, int width) throws IOException {
        if (!configured(c) || path == null || !path.startsWith("/Items/")) throw new IOException("no Jellyfin picture");
        return bytes(path + "&maxWidth=" + Math.max(160, Math.min(1280, width * 5 / 4)), MAX_PICTURE);
    }

    /** The user whose newest items show: jellyfin_user by name (an API key sees every user), else the key's own. */
    private static String user() throws IOException, org.json.JSONException {
        String id = userId;
        if (id != null) return id;
        String name = Config.JELLYFIN_USER.trim();
        if (name.isEmpty()) {
            try {
                id = new JSONObject(get("/Users/Me")).optString("Id", "");
            } catch (Status e) {
                // An API key has no user of its own: with only one user on the server, that one.
                JSONArray users = new JSONArray(get("/Users"));
                if (users.length() != 1) throw new IOException("set jellyfin_user in config.properties");
                id = users.getJSONObject(0).optString("Id", "");
            }
        } else {
            JSONArray users = new JSONArray(get("/Users"));
            for (int i = 0; i < users.length() && (id == null || id.isEmpty()); i++) {
                JSONObject u = users.getJSONObject(i);
                if (name.equalsIgnoreCase(u.optString("Name"))) id = u.optString("Id", "");
            }
            if (id == null || id.isEmpty()) throw new IOException("no Jellyfin user named " + name);
        }
        if (!id.matches("[0-9a-fA-F-]{32,36}")) throw new IOException("no Jellyfin user");
        userId = id;
        return id;
    }

    /** An HTTP status other than 200. */
    private static final class Status extends IOException {
        final int code;

        Status(int code, String path) {
            super("HTTP " + code + " on " + path.replaceAll("\\?.*", ""));
            this.code = code;
        }
    }

    private static String get(String path) throws IOException {
        return new String(bytes(path, MAX_JSON), "UTF-8");
    }

    private static byte[] bytes(String path, int max) throws IOException {
        String server = Config.JELLYFIN_SERVER.trim();
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        HttpURLConnection http = (HttpURLConnection) new URL(server + path).openConnection();
        try {
            // Never followed: the key would go along to wherever a redirect points.
            http.setInstanceFollowRedirects(false);
            http.setConnectTimeout(8_000);
            http.setReadTimeout(15_000);
            http.setRequestProperty("Authorization", "MediaBrowser Client=\"Free My TV\", Device=\"TV\", "
                    + "DeviceId=\"freemytv\", Version=\"1\", Token=\"" + key + "\"");
            http.setRequestProperty("Accept", "application/json, image/*");
            int code = http.getResponseCode();
            if (code != 200) throw new Status(code, path);
            try (InputStream in = http.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    if (out.size() > max) throw new IOException("too large: " + path.replaceAll("\\?.*", ""));
                }
                return out.toByteArray();
            }
        } finally {
            http.disconnect();
        }
    }
}
