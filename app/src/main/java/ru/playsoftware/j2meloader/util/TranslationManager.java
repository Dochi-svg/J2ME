package ru.playsoftware.j2meloader.util;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class TranslationManager {
    private static final Map<String, String> translationMap = new ConcurrentHashMap<>();
    private static final Map<String, String> reverseTranslationMap = new ConcurrentHashMap<>();
    private static final Map<String, String> dumpedStrings = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> sedangDiterjemahkan = new ConcurrentHashMap<>();
    
    private static File translationFile;
    private static File dumpFile;
    
    private static volatile boolean isDumpMode = true; 
    private static volatile boolean autoTranslateEnabled = true;
    
    private static final AtomicBoolean hasNewDataToSave = new AtomicBoolean(false);
    private static final AtomicBoolean hasNewTranslationToSave = new AtomicBoolean(false);
    
    private static ScheduledExecutorService saveScheduler;
    private static ScheduledExecutorService translationSaveScheduler;
    private static ExecutorService translateExecutor;

    public static synchronized void init(File gameDir) {
        if (gameDir == null) return;
        
        translationFile = new File(gameDir, "translation.json");
        dumpFile = new File(gameDir, "dump.json");
        
        loadTranslation();
        loadExistingDump();

        if (translateExecutor == null || translateExecutor.isShutdown()) {
            translateExecutor = Executors.newFixedThreadPool(4);
        }

        if (saveScheduler == null || saveScheduler.isShutdown()) {
            saveScheduler = Executors.newSingleThreadScheduledExecutor();
            saveScheduler.scheduleWithFixedDelay(TranslationManager::saveDumpInternal, 500, 500, TimeUnit.MILLISECONDS);
        }

        if (translationSaveScheduler == null || translationSaveScheduler.isShutdown()) {
            translationSaveScheduler = Executors.newSingleThreadScheduledExecutor();
            translationSaveScheduler.scheduleWithFixedDelay(TranslationManager::saveTranslationInternal, 1000, 1000, TimeUnit.MILLISECONDS);
        }
    }

    public static void loadTranslation() {
        translationMap.clear();
        reverseTranslationMap.clear();

        if (translationFile == null || !translationFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(translationFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            
            String jsonString = sb.toString().trim();
            if (jsonString.startsWith("\uFEFF")) {
                jsonString = jsonString.substring(1);
            }

            if (jsonString.isEmpty()) return;
            
            JSONObject json = new JSONObject(jsonString);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String val = json.getString(key);
                translationMap.put(key, val);
                reverseTranslationMap.put(val, key);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void loadExistingDump() {
        if (dumpFile == null || !dumpFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(dumpFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            
            String jsonString = sb.toString().trim();
            if (jsonString.startsWith("\uFEFF")) {
                jsonString = jsonString.substring(1);
            }

            if (jsonString.isEmpty()) return;

            JSONObject json = new JSONObject(jsonString);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                dumpedStrings.put(key, json.getString(key));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void saveDumpInternal() {
        if (!isDumpMode || !hasNewDataToSave.compareAndSet(true, false) || dumpFile == null) return;
        
        File tempFile = new File(dumpFile.getAbsolutePath() + ".tmp");
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> entry : dumpedStrings.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }

            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8)) {
                writer.write(json.toString(4));
            }
            
            if (tempFile.exists()) {
                if (dumpFile.exists()) dumpFile.delete();
                tempFile.renameTo(dumpFile);
            }
        } catch (Exception e) {
            hasNewDataToSave.set(true);
            e.printStackTrace();
        }
    }

    public static void saveTranslationInternal() {
        if (!hasNewTranslationToSave.compareAndSet(true, false) || translationFile == null) return;
        
        File tempFile = new File(translationFile.getAbsolutePath() + ".tmp");
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, String> entry : translationMap.entrySet()) {
                json.put(entry.getKey(), entry.getValue());
            }

            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8)) {
                writer.write(json.toString(4));
            }
            
            if (tempFile.exists()) {
                if (translationFile.exists()) translationFile.delete();
                tempFile.renameTo(translationFile);
            }
        } catch (Exception e) {
            hasNewTranslationToSave.set(true);
            e.printStackTrace();
        }
    }

    public static synchronized void shutdownScheduler() {
        saveDumpInternal();
        saveTranslationInternal();

        if (saveScheduler != null && !saveScheduler.isShutdown()) {
            saveScheduler.shutdown();
            saveScheduler = null;
        }
        if (translationSaveScheduler != null && !translationSaveScheduler.isShutdown()) {
            translationSaveScheduler.shutdown();
            translationSaveScheduler = null;
        }
        if (translateExecutor != null && !translateExecutor.isShutdown()) {
            translateExecutor.shutdown();
            translateExecutor = null;
        }
    }

    public static String processString(String original) {
        if (original == null) return original;
        
        String trimmed = original.trim();
        if (trimmed.isEmpty() || trimmed.length() <= 1 || trimmed.matches("^\\d+$")) {
            return original;
        }

        if (translationMap.containsKey(trimmed)) {
            if (dumpedStrings.remove(trimmed) != null) {
                hasNewDataToSave.set(true);
            }
            return original.replace(trimmed, translationMap.get(trimmed));
        }

        if (reverseTranslationMap.containsKey(trimmed)) {
            return original;
        }

        if (autoTranslateEnabled && !sedangDiterjemahkan.containsKey(trimmed)) {
            sedangDiterjemahkan.put(trimmed, Boolean.TRUE);
            if (translateExecutor != null && !translateExecutor.isShutdown()) {
                translateExecutor.execute(() -> terjemahkanViaAPI(trimmed));
            }
        }

        if (isDumpMode && !dumpedStrings.containsKey(trimmed)) {
            dumpedStrings.put(trimmed, trimmed);
            hasNewDataToSave.set(true);
        }

        return original;
    }

    private static void terjemahkanViaAPI(String teks) {
        HttpURLConnection conn = null;
        try {
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=id&dt=t&q=" 
                    + URLEncoder.encode(teks, "UTF-8");
            
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    JSONArray jsonArray = new JSONArray(response.toString());
                    if (jsonArray.length() > 0 && !jsonArray.isNull(0)) {
                        JSONArray sentences = jsonArray.getJSONArray(0);
                        StringBuilder translatedResult = new StringBuilder();

                        for (int i = 0; i < sentences.length(); i++) {
                            if (!sentences.isNull(i)) {
                                JSONArray sentence = sentences.getJSONArray(i);
                                if (!sentence.isNull(0)) {
                                    translatedResult.append(sentence.getString(0));
                                }
                            }
                        }

                        String hasilTranslate = translatedResult.toString();

                        if (!hasilTranslate.isEmpty() && !hasilTranslate.equals(teks)) {
                            translationMap.put(teks, hasilTranslate);
                            reverseTranslationMap.put(hasilTranslate, teks);
                            hasNewTranslationToSave.set(true);

                            if (dumpedStrings.remove(teks) != null) {
                                hasNewDataToSave.set(true);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            sedangDiterjemahkan.remove(teks);
        }
    }

    public static void setDumpMode(boolean enabled) { isDumpMode = enabled; }
    public static boolean isDumpMode() { return isDumpMode; }
    public static void setAutoTranslateEnabled(boolean enabled) { autoTranslateEnabled = enabled; }
    public static boolean isAutoTranslateEnabled() { return autoTranslateEnabled; }
}

class GraphicsUtils {

    public static void drawStringIntercepted(Canvas canvas, Paint paint, String str, float x, float y, int anchor) {
        if (str == null || str.trim().isEmpty() || canvas == null || paint == null) return;

        String teksBaru = TranslationManager.processString(str);

        Typeface oldTypeface = paint.getTypeface();
        boolean oldAntiAlias = paint.isAntiAlias();
        boolean oldSubpixel = paint.isSubpixelText();
        float originalTextSize = paint.getTextSize();

        // Kurangi font 2.5px dari ukuran asli game agar pas di box
        float targetFontSize = Math.max((originalTextSize > 0 ? originalTextSize : 12f) - 2.5f, 8f);

        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        paint.setTextSize(targetFontSize);
        paint.setAntiAlias(true);
        paint.setSubpixelText(true);

        try {
            float widthAsli = paint.measureText(str.trim());
            renderAndScaleText(canvas, paint, str.trim(), teksBaru, x, y, anchor);
        } finally {
            paint.setTextSize(originalTextSize);
            paint.setTypeface(oldTypeface);
            paint.setAntiAlias(oldAntiAlias);
            paint.setSubpixelText(oldSubpixel);
        }
    }

    private static void renderAndScaleText(Canvas canvas, Paint paint, String teksAsli, String teksRender, float x, float y, int anchor) {
        float widthAsli = paint.measureText(teksAsli);
        float widthBaru = paint.measureText(teksRender);

        Paint.FontMetrics fm = paint.getFontMetrics();

        float drawX = alignX(x, widthAsli, anchor);
        float drawY = alignY(y, fm, anchor);

        boolean butuhScaling = !teksRender.equals(teksAsli) && (widthBaru > widthAsli) && (widthBaru > 0) && (widthAsli > 0);

        canvas.save();
        try {
            if (butuhScaling) {
                float scaleX = widthAsli / widthBaru;
                canvas.translate(drawX, drawY);
                canvas.scale(scaleX, 1.0f);
                drawShadowAndText(canvas, paint, teksRender, 0, 0);
            } else {
                drawShadowAndText(canvas, paint, teksRender, drawX, drawY);
            }
        } finally {
            canvas.restore();
        }
    }

    private static void drawShadowAndText(Canvas canvas, Paint paint, String text, float x, float y) {
        int originalColor = paint.getColor();

        paint.setColor(Color.argb(160, 0, 0, 0));
        canvas.drawText(text, x + 1f, y + 1f, paint);

        paint.setColor(originalColor);
        canvas.drawText(text, x, y, paint);
    }

    private static float alignX(float x, float width, int anchor) {
        if ((anchor & 1) != 0) return x - (width / 2f);
        if ((anchor & 8) != 0) return x - width;
        return x;
    }

    private static float alignY(float y, Paint.FontMetrics fm, int anchor) {
        if ((anchor & 16) != 0) return y - fm.ascent;
        if ((anchor & 32) != 0) return y - (fm.ascent + fm.descent)/2f;
        if ((anchor & 64) != 0) return y - fm.descent;
        return y;
    }
}
