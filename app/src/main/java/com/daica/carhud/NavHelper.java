package com.daica.carhud;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tìm địa điểm (Nominatim) + tìm đường (OSRM) miễn phí, và theo dõi tiến trình trên tuyến. */
public final class NavHelper {

    public static class Place {
        public String name, detail;
        public double lat, lon;
        @Override public String toString() { return name; }
    }

    public static class Step {
        public double lat, lon;
        public String type, modifier, name;
        public int exit;
    }

    public static class Route {
        public double[] lat, lon;
        public double[] cum; // quãng đường cộng dồn tới từng điểm (m)
        public double distance, duration;
        public final List<Step> steps = new ArrayList<>();
        public String destName = "";
        public double destLat, destLon;
        // trạng thái khi chạy
        public int nearIdx = 0, stepIdx = 1, offCount = 0;
    }

    // ---------------- tìm địa điểm ----------------
    public static List<Place> search(String q, double nearLat, double nearLon) throws Exception {
        try {
            return searchNominatim(q, nearLat, nearLon);
        } catch (Exception e) {
            return searchPhoton(q, nearLat, nearLon);
        }
    }

    /** Dự phòng: Photon (komoot), miễn phí, không cần khoá. */
    private static List<Place> searchPhoton(String q, double nearLat, double nearLon) throws Exception {
        StringBuilder u = new StringBuilder("https://photon.komoot.io/api/?limit=8&q=");
        u.append(URLEncoder.encode(q, "UTF-8"));
        if (!Double.isNaN(nearLat)) u.append(String.format(Locale.US, "&lat=%.4f&lon=%.4f", nearLat, nearLon));
        JSONArray a = new JSONObject(Net.get(u.toString())).getJSONArray("features");
        List<Place> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject f = a.getJSONObject(i);
            JSONObject pr = f.getJSONObject("properties");
            JSONArray co = f.getJSONObject("geometry").getJSONArray("coordinates");
            Place p = new Place();
            p.name = pr.optString("name", pr.optString("street", "?"));
            StringBuilder d = new StringBuilder();
            for (String k : new String[]{"housenumber", "street", "district", "city", "state"}) {
                String v = pr.optString(k, "");
                if (v.length() > 0 && !v.equals(p.name)) { if (d.length() > 0) d.append(", "); d.append(v); }
            }
            p.detail = d.toString();
            p.lon = co.getDouble(0);
            p.lat = co.getDouble(1);
            out.add(p);
        }
        return out;
    }

    private static List<Place> searchNominatim(String q, double nearLat, double nearLon) throws Exception {
        StringBuilder u = new StringBuilder("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&addressdetails=0&accept-language=vi&countrycodes=vn&q=");
        u.append(URLEncoder.encode(q, "UTF-8"));
        if (!Double.isNaN(nearLat)) {
            // ưu tiên kết quả gần vị trí hiện tại (khung ~1 độ)
            u.append(String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f", nearLon - 1, nearLat + 1, nearLon + 1, nearLat - 1));
        }
        JSONArray a = new JSONArray(Net.get(u.toString()));
        List<Place> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Place p = new Place();
            String dn = o.optString("display_name", "");
            String nm = o.optString("name", "");
            if (nm.length() == 0) { int c = dn.indexOf(','); nm = c > 0 ? dn.substring(0, c) : dn; }
            p.name = nm;
            p.detail = dn;
            p.lat = o.getDouble("lat");
            p.lon = o.getDouble("lon");
            out.add(p);
        }
        return out;
    }

    // ---------------- tìm đường ----------------
    public static Route route(double fromLat, double fromLon, double toLat, double toLon) throws Exception {
        String path = String.format(Locale.US, "/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=polyline&steps=true",
                fromLon, fromLat, toLon, toLat);
        String body;
        try {
            body = Net.get("https://router.project-osrm.org" + path);
        } catch (Exception e) {
            body = Net.get("http://router.project-osrm.org" + path);
        }
        JSONObject j = new JSONObject(body);
        if (!"Ok".equals(j.optString("code"))) throw new Exception("Không tìm được đường");
        JSONObject r = j.getJSONArray("routes").getJSONObject(0);
        Route rt = new Route();
        rt.distance = r.getDouble("distance");
        rt.duration = r.getDouble("duration");
        List<double[]> pts = decode(r.getString("geometry"));
        int n = pts.size();
        rt.lat = new double[n]; rt.lon = new double[n]; rt.cum = new double[n];
        for (int i = 0; i < n; i++) {
            rt.lat[i] = pts.get(i)[0]; rt.lon[i] = pts.get(i)[1];
            if (i > 0) rt.cum[i] = rt.cum[i - 1] + dist(rt.lat[i - 1], rt.lon[i - 1], rt.lat[i], rt.lon[i]);
        }
        JSONArray legs = r.getJSONArray("legs");
        for (int l = 0; l < legs.length(); l++) {
            JSONArray st = legs.getJSONObject(l).getJSONArray("steps");
            for (int i = 0; i < st.length(); i++) {
                JSONObject s = st.getJSONObject(i);
                JSONObject m = s.getJSONObject("maneuver");
                Step x = new Step();
                JSONArray loc = m.getJSONArray("location");
                x.lon = loc.getDouble(0); x.lat = loc.getDouble(1);
                x.type = m.optString("type", "");
                x.modifier = m.optString("modifier", "");
                x.exit = m.optInt("exit", 0);
                x.name = s.optString("name", "");
                if (x.name.length() == 0) x.name = s.optString("ref", "");
                rt.steps.add(x);
            }
        }
        rt.destLat = toLat; rt.destLon = toLon;
        return rt;
    }

    private static List<double[]> decode(String enc) {
        List<double[]> out = new ArrayList<>();
        int i = 0, lat = 0, lon = 0;
        while (i < enc.length()) {
            int b, sh = 0, res = 0;
            do { b = enc.charAt(i++) - 63; res |= (b & 0x1f) << sh; sh += 5; } while (b >= 0x20);
            lat += (res & 1) != 0 ? ~(res >> 1) : (res >> 1);
            sh = 0; res = 0;
            do { b = enc.charAt(i++) - 63; res |= (b & 0x1f) << sh; sh += 5; } while (b >= 0x20);
            lon += (res & 1) != 0 ? ~(res >> 1) : (res >> 1);
            out.add(new double[]{lat / 1e5, lon / 1e5});
        }
        return out;
    }

    public static double dist(double la1, double lo1, double la2, double lo2) {
        double R = 6371000, dLa = Math.toRadians(la2 - la1), dLo = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLa / 2) * Math.sin(dLa / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Cập nhật vị trí. Trả về khoảng cách lệch khỏi tuyến (m). */
    public static double update(Route r, double lat, double lon) {
        int n = r.lat.length;
        int from = Math.max(0, r.nearIdx - 5), to = Math.min(n - 1, r.nearIdx + 150);
        double best = Double.MAX_VALUE;
        int bi = r.nearIdx;
        for (int i = from; i <= to; i++) {
            double d = dist(lat, lon, r.lat[i], r.lon[i]);
            if (d < best) { best = d; bi = i; }
        }
        if (best > 200) { // tìm lại trên cả tuyến
            for (int i = 0; i < n; i++) {
                double d = dist(lat, lon, r.lat[i], r.lon[i]);
                if (d < best) { best = d; bi = i; }
            }
        }
        r.nearIdx = bi;
        // bước kế tiếp: bước đầu tiên có điểm rẽ nằm phía trước
        while (r.stepIdx < r.steps.size() - 1) {
            Step s = r.steps.get(r.stepIdx);
            if (dist(lat, lon, s.lat, s.lon) < 25 || stepBehind(r, s)) r.stepIdx++;
            else break;
        }
        return best;
    }

    private static boolean stepBehind(Route r, Step s) {
        // điểm rẽ gần nhất trên tuyến nằm trước vị trí hiện tại?
        int n = r.lat.length, bi = 0;
        double best = Double.MAX_VALUE;
        for (int i = Math.max(0, r.nearIdx - 200); i < Math.min(n, r.nearIdx + 400); i++) {
            double d = dist(s.lat, s.lon, r.lat[i], r.lon[i]);
            if (d < best) { best = d; bi = i; }
        }
        return bi < r.nearIdx - 2;
    }

    public static double remaining(Route r) {
        return Math.max(0, r.distance > 0 ? r.cum[r.cum.length - 1] - r.cum[r.nearIdx] : 0);
    }

    public static Step nextStep(Route r) {
        if (r.steps.isEmpty()) return null;
        return r.steps.get(Math.min(r.stepIdx, r.steps.size() - 1));
    }

    /** Câu chỉ dẫn tiếng Việt cho một bước. */
    public static String text(Step s) {
        String t = s.type, m = s.modifier;
        if ("arrive".equals(t)) return "Đến nơi";
        if ("depart".equals(t)) return "Xuất phát";
        if ("roundabout".equals(t) || "rotary".equals(t) || "roundabout turn".equals(t))
            return s.exit > 0 ? "Vòng xuyến, lối ra thứ " + s.exit : "Đi vào vòng xuyến";
        if ("exit roundabout".equals(t) || "exit rotary".equals(t)) return "Ra khỏi vòng xuyến";
        String dir = dir(m);
        if ("merge".equals(t)) return "Nhập làn" + (dir.length() > 0 ? " " + side(m) : "");
        if ("fork".equals(t)) return "Đi nhánh " + side(m);
        if ("on ramp".equals(t)) return "Vào đường nhánh " + side(m);
        if ("off ramp".equals(t)) return "Ra đường nhánh " + side(m);
        if ("end of road".equals(t)) return "Cuối đường " + dir.toLowerCase(Locale.ROOT);
        if ("continue".equals(t) || "new name".equals(t)) return "straight".equals(m) || m.length() == 0 ? "Đi thẳng" : dir;
        return dir.length() > 0 ? dir : "Đi tiếp";
    }

    private static String dir(String m) {
        if (m == null) return "";
        switch (m) {
            case "right": return "Rẽ phải";
            case "left": return "Rẽ trái";
            case "slight right": return "Chếch phải";
            case "slight left": return "Chếch trái";
            case "sharp right": return "Rẽ gắt phải";
            case "sharp left": return "Rẽ gắt trái";
            case "uturn": return "Quay đầu";
            case "straight": return "Đi thẳng";
        }
        return "";
    }

    private static String side(String m) {
        if (m != null && m.contains("right")) return "phải";
        if (m != null && m.contains("left")) return "trái";
        return "giữa";
    }

    public static String fmtDist(double m) {
        if (m < 1000) return (Math.round(m / 10) * 10) + " m";
        return String.format(Locale.US, "%.1f km", m / 1000).replace('.', ',');
    }

    private NavHelper() {}
}
