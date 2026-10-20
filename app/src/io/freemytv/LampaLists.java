package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Your Lampa lists (Watch later, Bookmarks, History...), sent by the Lampa plugin to LampaBridge on this TV, kept in
 * memory in the :home process and saved to files/lampa.json. Lists hold "type:id" keys (movies and shows are numbered
 * separately on TMDB) into the cards the plugin sent along; what is malformed is dropped, never fatal.
 */
final class LampaLists {
    private LampaLists() {}

    static final String PKG = "top.rootu.lampa";

    /** One title, as the plugin sent it. */
    static final class Entry {
        final String type, source, title, original, poster, backdrop;
        final int id, year;
        final double rating;

        Entry(String type, int id, String source, String title, String original, int year, String poster, String backdrop,
              double rating) {
            this.type = type;
            this.id = id;
            this.source = source;
            this.title = title;
            this.original = original;
            this.year = year;
            this.poster = poster;
            this.backdrop = backdrop;
            this.rating = rating;
        }

        String key() { return type + ":" + id; }
    }

    interface Listener { void onLampa(); }

    private static final int MAX_LISTS = 16, MAX_PER_LIST = 100, CARDS = 3;
    /** What the home screen's tabs show from Lampa, first to last (the other lists stay stored). */
    private static final String[] TAB_LISTS = {"wath", "continued", "scheduled", "book", "history"};
    private static final Pattern NAME = Pattern.compile("[a-z_]{1,24}"), SOURCE = Pattern.compile("[a-z0-9_]{1,20}");
    /** A TMDB image path, or an https address. */
    private static final Pattern IMAGE = Pattern.compile("/[A-Za-z0-9._-]{1,120}|https://[^\\s\"<>]{1,280}");

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile Map<String, List<Entry>> lists = Collections.emptyMap();
    /** Goes up on every change of the data (identical data sent again, as the plugin does every 10 minutes, isn't one). */
    private static volatile int version;
    private static boolean loaded;
    private static String content = "";   // what is held, without the time: an identical update changes nothing
    private static volatile boolean installed, checked;
    private static int countVersion = -1, countValue;
    private static final Object CACHE = new Object();   // not the class lock: a save in progress must not stall the home screen

    static void addListener(Listener l) { if (!LISTENERS.contains(l)) LISTENERS.add(l); }
    static void removeListener(Listener l) { LISTENERS.remove(l); }

    static List<Entry> list(String name) {
        List<Entry> l = lists.get(name);
        return l == null ? Collections.<Entry>emptyList() : l;
    }

    /** Reads what is saved and checks that Lampa is installed. Off the UI thread. */
    static void refresh(Context c) {
        load(c);
        installed = available(c);
        checked = true;
    }

    static boolean available(Context c) {
        return HomeApps.launchIntent(c.getPackageManager(), PKG) != null;
    }

    /** Titles the tabs can draw from (distinct, across the lists they use); 0 without Lampa. For the settings status. */
    static int count(Context c) {
        if (!checked) refresh(c);
        if (!installed) return 0;
        synchronized (CACHE) {
            if (countVersion != version) {
                Set<String> seen = new HashSet<>();
                for (String name : TAB_LISTS) for (Entry e : list(name)) seen.add(e.key());
                countVersion = version;
                countValue = seen.size();
            }
            return countValue;
        }
    }

    // MARK: - Receiving and saving

    /** Reads the saved lists (once). */
    static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        File f = new File(c.getFilesDir(), "lampa.json");
        if (!f.exists() || f.length() > 2 * 1024 * 1024) return;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            Snapshot s = parse(new String(data, 0, read, StandardCharsets.UTF_8));
            if (s == null) return;
            lists = s.lists;
            content = s.toJson(false).toString();
            version++;
        } catch (IOException | JSONException e) {
            AppLog.w("Lampa: could not read the saved lists");
        }
    }

    /** A POST from the plugin. Returns how many lists it held, or -1 if it isn't a valid payload. */
    static synchronized int update(Context c, String body) {
        load(c);
        Snapshot s = parse(body);
        if (s == null) return -1;
        try {
            String canonical = s.toJson(false).toString();
            if (canonical.equals(content)) return s.lists.size();
            content = canonical;
            lists = s.lists;
            version++;
            save(c, s.toJson(true).toString());
        } catch (JSONException e) {
            return -1;
        }
        MAIN.post(() -> { for (Listener l : LISTENERS) l.onLampa(); });
        return s.lists.size();
    }

    private static void save(Context c, String json) {
        File dir = c.getFilesDir();
        File tmp = new File(dir, "lampa.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            AppLog.w("Lampa: could not save the lists: " + e.getMessage());
            return;
        }
        if (!tmp.renameTo(new File(dir, "lampa.json"))) AppLog.w("Lampa: could not replace the saved lists");
    }

    private static final class Snapshot {
        long at;
        Map<String, List<Entry>> lists = new LinkedHashMap<>();

        JSONObject toJson(boolean withTime) throws JSONException {
            JSONObject ids = new JSONObject(), cards = new JSONObject();
            for (Map.Entry<String, List<Entry>> l : lists.entrySet()) {
                JSONArray keys = new JSONArray();
                for (Entry e : l.getValue()) {
                    keys.put(e.key());
                    if (cards.has(e.key())) continue;
                    cards.put(e.key(), new JSONObject().put("id", e.id).put("type", e.type).put("source", e.source)
                            .put("title", e.title).put("original", e.original).put("year", e.year)
                            .put("poster", e.poster).put("backdrop", e.backdrop).put("rating", e.rating));
                }
                ids.put(l.getKey(), keys);
            }
            JSONObject o = new JSONObject().put("v", 1);
            if (withTime) o.put("at", at);
            return o.put("lists", ids).put("cards", cards);
        }
    }

    private static Snapshot parse(String text) {
        try {
            JSONObject root = new JSONObject(text);
            JSONObject ids = root.optJSONObject("lists"), cards = root.optJSONObject("cards");
            if (root.optInt("v") != 1 || ids == null || cards == null) return null;
            Snapshot s = new Snapshot();
            s.at = root.optLong("at");
            Map<String, Entry> made = new HashMap<>();
            Iterator<String> names = ids.keys();
            while (names.hasNext() && s.lists.size() < MAX_LISTS) {
                String name = names.next();
                JSONArray keys = ids.optJSONArray(name);
                if (keys == null || !NAME.matcher(name).matches()) continue;
                List<Entry> out = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < keys.length() && i < MAX_PER_LIST; i++) {
                    String key = keys.optString(i, "");
                    if (!seen.add(key)) continue;
                    Entry e = made.get(key);
                    if (e == null) {
                        e = entry(key, cards);
                        if (e != null) made.put(key, e);
                    }
                    if (e != null) out.add(e);
                }
                s.lists.put(name, Collections.unmodifiableList(out));
            }
            return s;
        } catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    private static Entry entry(String key, JSONObject cards) {
        int colon = key.indexOf(':');
        if (colon <= 0) return null;
        String type = key.substring(0, colon);
        if (!type.equals("movie") && !type.equals("tv")) return null;
        int id;
        try {
            id = Integer.parseInt(key.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        JSONObject o = cards.optJSONObject(key);
        if (id <= 0 || o == null) return null;
        String title = text(o, "title", 200), original = text(o, "original", 200);
        if (title.isEmpty()) title = original;
        if (title.isEmpty()) return null;
        String source = text(o, "source", 20);
        if (!SOURCE.matcher(source).matches()) source = "tmdb";
        int year = o.optInt("year");
        double rating = o.optDouble("rating", 0);
        return new Entry(type, id, source, title, original, year > 0 && year < 3000 ? year : 0,
                image(text(o, "poster", 300)), image(text(o, "backdrop", 300)),
                Double.isNaN(rating) ? 0 : Math.max(0, Math.min(10, rating)));
    }

    private static String text(JSONObject o, String name, int max) {
        if (o.isNull(name)) return "";
        String s = o.optString(name, "").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String image(String s) {
        return IMAGE.matcher(s).matches() ? s : "";
    }

    // MARK: - Titles for the home screen's tabs (merged into Recs.now)

    private static String picksKey = "";
    private static List<Recs.Pick> picksAll = Collections.emptyList(), picksMovies = picksAll, picksShows = picksAll;

    /**
     * The Lampa titles one tab shows (null = Explore: both kinds; "movie" or "tv" for the others), up to three:
     * Watch later first, then Continue, Scheduled and Bookmarks, then History, without repeats. Each carries its
     * list's name, year and rating in the line under the title. Built again only when the data or the language
     * changes; empty without Lampa on the TV.
     */
    static List<Recs.Pick> picks(String kind) {
        if (!installed) return Collections.emptyList();
        synchronized (CACHE) {
            return picksLocked(kind);
        }
    }

    private static List<Recs.Pick> picksLocked(String kind) {
        String key = version + "|" + Ui.language;
        if (!key.equals(picksKey)) {
            picksKey = key;
            List<Recs.Pick> all = new ArrayList<>(), movies = new ArrayList<>(), shows = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String name : TAB_LISTS) {
                String label = label(name);
                for (Entry e : list(name)) {
                    if (!seen.add(e.key())) continue;
                    Recs.Pick p = pick(e, label);
                    if (all.size() < CARDS) all.add(p);
                    List<Recs.Pick> own = e.type.equals("movie") ? movies : shows;
                    if (own.size() < CARDS) own.add(p);
                }
            }
            picksAll = Collections.unmodifiableList(all);
            picksMovies = Collections.unmodifiableList(movies);
            picksShows = Collections.unmodifiableList(shows);
        }
        return kind == null ? picksAll : kind.equals("movie") ? picksMovies : picksShows;
    }

    private static Recs.Pick pick(Entry e, String label) {
        Recs.Pick p = new Recs.Pick();
        p.id = e.id;
        p.kind = e.type;
        p.title = e.title;
        p.backdrop = !e.backdrop.isEmpty() ? e.backdrop : !e.poster.isEmpty() ? e.poster : null;
        StringBuilder meta = new StringBuilder(label);
        if (e.year > 0) meta.append(" · ").append(e.year);
        if (e.rating > 0) meta.append(" · ★ ").append(String.format(Locale.US, "%.1f", e.rating));
        p.meta = meta.toString();
        p.detailed = true;   // nothing to fetch: what the plugin sent is what shows
        p.lampa = true;
        p.source = e.source;
        return p;
    }

    private static String label(String name) {
        switch (name) {
            case "wath": return Ui.tr("Ver depois", "Watch later", "Ver después");
            case "continued": return Ui.tr("Continuar", "Continue", "Continuar");
            case "scheduled": return Ui.tr("Agendado", "Scheduled", "Programado");
            case "book": return Ui.tr("Favoritos", "Bookmarks", "Favoritos");
            default: return Ui.tr("Histórico", "History", "Historial");
        }
    }
}
