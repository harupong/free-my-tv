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
 * OK opens the item in Jellyfin's Android TV app. With it, Continue Watching's row becomes Recently Watched: what the
 * user played last on the server, finished or not (played). A TV without the key keeps the one row of Continue Watching.
 */
final class Jellyfin {
    private Jellyfin() {}

    static final String PKG = "org.jellyfin.androidtv";
    /** Jellyfin's Android TV app opens an item from its start screen with this extra (as its home screen rows do). */
    private static final String START = PKG + "/.ui.startup.StartupActivity";
    static final int MAX = 16;
    static final int LOADING = 0, OK = 1, FAILED = 2;
    /** Recently Watched's cards: as many as Continue Watching's row holds. */
    static final int PLAYED_MAX = WatchNext.MAX;
    private static final long FRESH_MS = 3 * 60_000L, PLAYED_FRESH_MS = 15_000L, RETRY_MS = 60_000L;
    private static final int MAX_JSON = 4 << 20, MAX_PICTURE = 8 << 20;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile String key = "";
    private static volatile long keyAt = -1;
    private static volatile String userId;

    /** One list read from the server: what's in memory, how the last read went, and when it was. */
    private static final class Feed {
        final long freshMs;
        volatile List<WatchNext.Item> items = Collections.emptyList();
        volatile int state = LOADING;
        volatile long readAt = -1;
        volatile boolean reading;

        Feed(long freshMs) {
            this.freshMs = freshMs;
        }
    }

    private interface Reader {
        List<WatchNext.Item> read() throws IOException, org.json.JSONException;
    }

    /** Recently Added, and Recently Watched (read again sooner: you come back from Jellyfin having watched something). */
    private static final Feed LATEST = new Feed(FRESH_MS), PLAYED = new Feed(PLAYED_FRESH_MS);

    /** A server in config.properties and its key sent to this TV (the key read again every 30 s at most). */
    static boolean configured(Context c) {
        if (Config.JELLYFIN_SERVER.trim().isEmpty()) return false;
        long now = SystemClock.uptimeMillis();
        if (keyAt < 0 || now - keyAt > 30_000) {
            String k = Secrets.get(c, Secrets.JELLYFIN_KEY);
            if (!k.equals(key)) {
                userId = null;   // another key may be another user's
                LATEST.readAt = -1;
                PLAYED.readAt = -1;
            }
            key = k;
            keyAt = now;
        }
        return !key.isEmpty();
    }

    /** Recently Added's LOADING (not read yet), OK or FAILED (the last read; the list keeps what came before). */
    static int state() {
        return LATEST.state;
    }

    /** The same for Recently Watched. */
    static int playedState() {
        return PLAYED.state;
    }

    /**
     * The newest items as they are now (off the main thread: the key may be read). When they're older than a few
     * minutes they're read again on Jellyfin's own thread, and `changed` runs on the main thread if they changed.
     */
    static List<WatchNext.Item> latest(Context c, Runnable changed) {
        return fetch(c, LATEST, Jellyfin::readLatest, changed);
    }

    /**
     * What was played last, finished or not, newest first and one card per show (its last episode), the same way:
     * Recently Watched, the row that takes Continue Watching's place when Jellyfin is set up.
     */
    static List<WatchNext.Item> played(Context c, Runnable changed) {
        return fetch(c, PLAYED, Jellyfin::readPlayed, changed);
    }

    private static List<WatchNext.Item> fetch(Context c, Feed f, Reader reader, Runnable changed) {
        if (!configured(c)) return Collections.emptyList();
        long now = SystemClock.uptimeMillis();
        long due = f.state == FAILED ? RETRY_MS : f.freshMs;
        if (!f.reading && (f.readAt < 0 || now - f.readAt > due)) {
            f.reading = true;
            f.readAt = now;
            WORKER.execute(() -> {
                int before = f.state;
                List<WatchNext.Item> old = f.items;
                try {
                    List<WatchNext.Item> fresh = reader.read();
                    if (!same(fresh, old)) f.items = fresh;
                    f.state = OK;
                } catch (Exception e) {   // never past this thread: :home's watchdog counts it as a crash
                    AppLog.w("jellyfin: " + e.getMessage());
                    f.state = FAILED;
                } finally {
                    f.readAt = SystemClock.uptimeMillis();
                    f.reading = false;
                }
                if (f.state != before || f.items != old) MAIN.post(changed);
            });
        }
        return f.items;
    }

    private static boolean same(List<WatchNext.Item> a, List<WatchNext.Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            WatchNext.Item x = a.get(i), y = b.get(i);
            if (!x.intent.equals(y.intent) || !x.title.equals(y.title) || x.position != y.position
                    || !java.util.Objects.equals(x.episode, y.episode)
                    || !java.util.Objects.equals(x.picture(), y.picture())) return false;
        }
        return true;
    }

    private static List<WatchNext.Item> readLatest() throws IOException, org.json.JSONException {
        String user = user();
        String query = "?userId=" + user + "&limit=" + MAX + "&groupItems=true&enableImageTypes=Primary,Backdrop,Thumb"
                + "&imageTypeLimit=1&fields=ParentId,DateCreated,DateLastMediaAdded";
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
            WatchNext.Item it = item(all.getJSONObject(i));
            if (it != null) out.add(it);
        }
        return out;
    }

    private static List<WatchNext.Item> readPlayed() throws IOException, org.json.JSONException {
        String user = user();
        // Sorted by when it was last played; never-played items come last and are left out below. Episodes of one
        // show collapse into one card, so a few more are asked for than shown.
        String query = "?userId=" + user + "&recursive=true&includeItemTypes=Movie,Episode,Video"
                + "&sortBy=DatePlayed&sortOrder=Descending&limit=" + (PLAYED_MAX * 4) + "&enableUserData=true"
                + "&enableImageTypes=Primary,Backdrop,Thumb&imageTypeLimit=1"
                + "&fields=ParentId,DateCreated,DateLastMediaAdded";
        String body;
        try {
            body = get("/Items" + query);
        } catch (Status e) {
            if (e.code != 404) throw e;
            body = get("/Users/" + user + "/Items" + query);   // servers before 10.9
        }
        JSONArray all = new JSONObject(body).optJSONArray("Items");
        List<WatchNext.Item> out = new ArrayList<>();
        java.util.Set<String> shows = new java.util.HashSet<>();
        for (int i = 0; all != null && i < all.length() && out.size() < PLAYED_MAX; i++) {
            JSONObject o = all.getJSONObject(i);
            JSONObject data = o.optJSONObject("UserData");
            if (data == null || data.optString("LastPlayedDate", "").isEmpty()) continue;
            if (!data.optBoolean("Played") && data.optLong("PlaybackPositionTicks") <= 0) continue;
            String series = o.optString("SeriesId", "");
            if (!series.isEmpty() && !shows.add(series)) continue;
            WatchNext.Item it = item(o);
            if (it == null) continue;
            // Half-watched ones keep their bar and "min left"; finished ones have none (position 0).
            if (!data.optBoolean("Played")) {
                it.position = data.optLong("PlaybackPositionTicks") / 10_000;
                it.duration = o.optLong("RunTimeTicks") / 10_000;
            }
            out.add(it);
        }
        return out;
    }

    /** A card for one of the server's items, or null when it has no usable id or title. */
    private static WatchNext.Item item(JSONObject o) {
        String id = o.optString("Id", "");
        if (!id.matches("[0-9a-fA-F-]{32,36}")) return null;
        WatchNext.Item it = new WatchNext.Item(PKG, picture(o), true);
        String type = o.optString("Type");
        String series = o.optString("SeriesName", "");
        it.title = "Episode".equals(type) && !series.isEmpty() ? series : o.optString("Name", "");
        if (it.title.isEmpty()) return null;
        // An episode's air date, as Jellyfin shows it; else when it came to the server (a show grouped in Recently
        // Added carries its newest episode's). A recording added after midnight still shows the day it aired.
        String aired = "Episode".equals(type) ? o.optString("PremiereDate", "") : "";
        if (parseDate(aired) > 0) {
            it.episode = Ui.trf("Aired {date}", "date", shortDate(aired));
        } else {
            String added = o.optString("DateLastMediaAdded", "");
            if (parseDate(added) <= 0) added = o.optString("DateCreated", "");
            if (parseDate(added) > 0) it.episode = Ui.trf("Added {date}", "date", shortDate(added));
        }
        it.app = "Jellyfin";
        it.intent = "intent:#Intent;component=" + START + ";S.ItemId=" + id + ";end";
        return it;
    }

    /** Jellyfin's "2026-11-17T12:34:56.1234567Z" (UTC) in ms, or 0. */
    private static long parseDate(String s) {
        if (s.length() < 19) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            long at = f.parse(s.substring(0, 19)).getTime();
            return at > 0 ? at : 0;   // year 1 means none
        } catch (java.text.ParseException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * "Nov 17" in the TV's language, with the year when it isn't this one. A date with no time (midnight UTC, as an
     * air date is kept) stays that day; a moment is shown in the TV's time zone.
     */
    private static String shortDate(String s) {
        java.util.TimeZone zone = s.startsWith("T00:00:00", 10) ? java.util.TimeZone.getTimeZone("UTC")
                : java.util.TimeZone.getDefault();
        long at = parseDate(s);
        java.util.Calendar then = java.util.Calendar.getInstance(zone), now = java.util.Calendar.getInstance(zone);
        then.setTimeInMillis(at);
        String skeleton = then.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) ? "MMMd" : "yMMMd";
        java.util.Locale loc = Ui.locale();
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                android.text.format.DateFormat.getBestDateTimePattern(loc, skeleton), loc);
        f.setTimeZone(zone);
        return f.format(new java.util.Date(at));
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
