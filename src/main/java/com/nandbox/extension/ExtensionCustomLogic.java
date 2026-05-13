package com.nandbox.extension;

import com.nandbox.bots.api.Nandbox;
import com.nandbox.bots.api.NandboxClient;
import com.nandbox.bots.api.data.*;
import com.nandbox.bots.api.inmessages.*;
import com.nandbox.bots.api.outmessages.*;
import com.nandbox.bots.api.util.*;
import com.nandbox.bots.api.test.*;
import net.minidev.json.*;
import net.minidev.json.parser.JSONParser;
import com.nandbox.extension.ExtensionAdapter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Properties;

public class ExtensionCustomLogic extends ExtensionAdapter {
    private Nandbox.Api api;
    private String weatherApiKey;
    private String weatherApiBaseUrl;

    public static void main(String[] args) throws Exception {
        String TOKEN = "";
        Properties properties = new Properties();
        FileInputStream input = null;
        try {
            input = new FileInputStream("config.properties");
            properties.load(input);
            TOKEN = properties.getProperty("Token");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                }
            }
        }

        NandboxClient client = NandboxClient.get();
        client.connect(TOKEN, new ExtensionCustomLogic());
    }

    @Override
    public void onConnect(Nandbox.Api api) {
        this.api = api;
        Properties p = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream("config.properties");
            p.load(in);
        } catch (Exception e) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception e) {
                }
            }
        }
        this.weatherApiKey = safeTrim(p.getProperty("WEATHER_API_KEY"));
        this.weatherApiBaseUrl = safeTrim(p.getProperty("WEATHER_API_BASE_URL"));
        if (this.weatherApiBaseUrl == null || this.weatherApiBaseUrl.length() == 0) {
            this.weatherApiBaseUrl = "http://api.weatherapi.com/v1/";
        }
    }

    @Override
    public void onReceive(IncomingMessage incomingMsg) {
        if (incomingMsg == null || incomingMsg.getChat() == null || incomingMsg.getFrom() == null) {
            return;
        }

        String chatId = incomingMsg.getChat().getId();
        String text = incomingMsg.getText();
        String reference = Utils.getUniqueId();
        String userId = incomingMsg.getFrom().getId();
        String appId = incomingMsg.getAppId();
        Integer chatSettings = incomingMsg.getChatSettings();

        if (text == null) {
            return;
        }
        text = text.trim();
        if (text.length() == 0) {
            return;
        }

        try {
            if (equalsAnyIgnoreCase(text, "/start", "start", "help", "/help")) {
                String help = "Your Personal Weather Guide\n\n" +
                        "Commands:\n" +
                        "/weather <city>  - Current weather for a city\n" +
                        "/weather lat:<lat> lon:<lon> - Current weather by coordinates\n\n" +
                        "Examples:\n" +
                        "/weather London\n" +
                        "/weather lat:40.7128 lon:-74.0060";
                api.sendText(chatId, help, reference, null, userId, 0, false, chatSettings, null, null, null, appId);
                return;
            }

            if (startsWithIgnoreCase(text, "/weather") || startsWithIgnoreCase(text, "weather")) {
                String query = extractWeatherQuery(text);
                if (query == null || query.length() == 0) {
                    api.sendText(chatId, "Please provide a city or coordinates. Example: /weather London", reference, null, userId, 0, false, chatSettings, null, null, null, appId);
                    return;
                }

                if (weatherApiKey == null || weatherApiKey.length() == 0) {
                    api.sendText(chatId, "Weather service is not configured. Please set WEATHER_API_KEY in config.properties", reference, null, userId, 0, false, chatSettings, null, null, null, appId);
                    return;
                }

                WeatherResult result = fetchCurrentWeather(query);
                if (result == null) {
                    api.sendText(chatId, "Couldn't fetch weather right now. Please try again later.", reference, null, userId, 0, false, chatSettings, null, null, null, appId);
                    return;
                }

                api.sendText(chatId, formatWeather(result), reference, null, userId, 0, false, chatSettings, null, null, null, appId);
                return;
            }

            api.sendText(chatId, "Type /weather <city> to get current weather. مثال: /weather London", reference, null, userId, 0, false, chatSettings, null, null, null, appId);
        } catch (Exception e) {
            try {
                api.sendText(chatId, "An error occurred while processing your request.", reference, null, userId, 0, false, chatSettings, null, null, null, appId);
            } catch (Exception ex) {
            }
        }
    }

    @Override
    public void onReceive(JSONObject obj) {
        if (obj == null) {
            return;
        }
        Object hasMsg = obj.get("message");
        if (hasMsg != null) {
            return;
        }
        Object hasIncoming = obj.get("incoming_message");
        if (hasIncoming != null) {
            return;
        }
    }

    private WeatherResult fetchCurrentWeather(String query) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            String base = weatherApiBaseUrl;
            if (!base.endsWith("/")) {
                base = base + "/";
            }
            String urlStr = base + "current.json?key=" + URLEncoder.encode(weatherApiKey, "UTF-8") + "&q=" + URLEncoder.encode(query, "UTF-8") + "&aqi=no";
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setUseCaches(false);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                in = conn.getInputStream();
            } else {
                in = conn.getErrorStream();
            }
            if (in == null) {
                return null;
            }
            String body = readAll(in);
            if (body == null || body.length() == 0) {
                return null;
            }

            JSONParser parser = new JSONParser(JSONParser.MODE_PERMISSIVE);
            Object parsed = parser.parse(body);
            if (!(parsed instanceof JSONObject)) {
                return null;
            }
            JSONObject root = (JSONObject) parsed;
            if (root.get("error") != null) {
                return parseErrorAsResult(root);
            }
            JSONObject location = (JSONObject) root.get("location");
            JSONObject current = (JSONObject) root.get("current");
            if (location == null || current == null) {
                return null;
            }

            WeatherResult r = new WeatherResult();
            r.name = asString(location.get("name"));
            r.region = asString(location.get("region"));
            r.country = asString(location.get("country"));
            r.localtime = asString(location.get("localtime"));
            r.tempC = asDouble(current.get("temp_c"));
            r.feelslikeC = asDouble(current.get("feelslike_c"));
            r.humidity = asInt(current.get("humidity"));
            r.windKph = asDouble(current.get("wind_kph"));
            r.windDir = asString(current.get("wind_dir"));
            r.isDay = asInt(current.get("is_day"));
            r.uv = asDouble(current.get("uv"));

            JSONObject condition = (JSONObject) current.get("condition");
            if (condition != null) {
                r.conditionText = asString(condition.get("text"));
            }
            return r;
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception e) {
                }
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception e) {
                }
            }
        }
    }

    private WeatherResult parseErrorAsResult(JSONObject root) {
        try {
            JSONObject err = (JSONObject) root.get("error");
            if (err == null) {
                return null;
            }
            String msg = asString(err.get("message"));
            WeatherResult r = new WeatherResult();
            r.errorMessage = msg;
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    private String formatWeather(WeatherResult r) {
        if (r.errorMessage != null && r.errorMessage.length() > 0) {
            return "Weather API error: " + r.errorMessage;
        }
        String place = safeJoin(", ", new String[]{r.name, r.region, r.country});
        String when = r.localtime != null ? r.localtime : "";
        String cond = r.conditionText != null ? r.conditionText : "";
        String day = (r.isDay != null && r.isDay.intValue() == 1) ? "Day" : "Night";

        String s = "Current Weather\n" +
                place + "\n" +
                (when.length() > 0 ? ("Local time: " + when + "\n") : "") +
                (cond.length() > 0 ? ("Condition: " + cond + " (" + day + ")\n") : "") +
                (r.tempC != null ? ("Temperature: " + format1(r.tempC) + " °C\n") : "") +
                (r.feelslikeC != null ? ("Feels like: " + format1(r.feelslikeC) + " °C\n") : "") +
                (r.humidity != null ? ("Humidity: " + r.humidity + "%\n") : "") +
                (r.windKph != null ? ("Wind: " + format1(r.windKph) + " kph" + (r.windDir != null && r.windDir.length() > 0 ? (" " + r.windDir) : "") + "\n") : "") +
                (r.uv != null ? ("UV: " + format1(r.uv)) : "");
        return s.trim();
    }

    private static String extractWeatherQuery(String text) {
        String t = text.trim();
        int idx = t.indexOf(' ');
        if (idx < 0) {
            return "";
        }
        String rest = t.substring(idx + 1).trim();
        if (rest.length() == 0) {
            return "";
        }

        String lat = null;
        String lon = null;
        String[] parts = rest.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            int c = p.indexOf(':');
            if (c > 0) {
                String k = p.substring(0, c).toLowerCase();
                String v = p.substring(c + 1);
                if ("lat".equals(k)) {
                    lat = v;
                } else if ("lon".equals(k) || "lng".equals(k) || "long".equals(k)) {
                    lon = v;
                }
            }
        }
        if (lat != null && lon != null && lat.length() > 0 && lon.length() > 0) {
            return lat + "," + lon;
        }
        return rest;
    }

    private static boolean startsWithIgnoreCase(String text, String prefix) {
        if (text == null || prefix == null) {
            return false;
        }
        if (text.length() < prefix.length()) {
            return false;
        }
        return text.substring(0, prefix.length()).toLowerCase().equals(prefix.toLowerCase());
    }

    private static boolean equalsAnyIgnoreCase(String text, String a, String b, String c, String d) {
        if (text == null) {
            return false;
        }
        String t = text.trim().toLowerCase();
        return (a != null && t.equals(a.toLowerCase())) ||
                (b != null && t.equals(b.toLowerCase())) ||
                (c != null && t.equals(c.toLowerCase())) ||
                (d != null && t.equals(d.toLowerCase()));
    }

    private static String safeTrim(String s) {
        return s == null ? null : s.trim();
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Double asDouble(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return new Double(((Number) o).doubleValue());
        }
        try {
            return new Double(Double.parseDouble(String.valueOf(o)));
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer asInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return new Integer(((Number) o).intValue());
        }
        try {
            return new Integer(Integer.parseInt(String.valueOf(o)));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuffer sb = new StringBuffer();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) != -1) {
            sb.append(new String(buf, 0, r, "UTF-8"));
        }
        return sb.toString();
    }

    private static String safeJoin(String sep, String[] arr) {
        if (arr == null) {
            return "";
        }
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < arr.length; i++) {
            String s = arr[i];
            if (s == null) {
                continue;
            }
            s = s.trim();
            if (s.length() == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static String format1(Double d) {
        if (d == null) {
            return "";
        }
        long scaled = Math.round(d.doubleValue() * 10.0);
        long whole = scaled / 10;
        long frac = Math.abs(scaled % 10);
        return String.valueOf(whole) + "." + String.valueOf(frac);
    }

    private static class WeatherResult {
        String name;
        String region;
        String country;
        String localtime;
        String conditionText;
        Double tempC;
        Double feelslikeC;
        Integer humidity;
        Double windKph;
        String windDir;
        Integer isDay;
        Double uv;
        String errorMessage;
    }
}
