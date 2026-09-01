package ru.playsoftware.j2meloader.util;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
    // Cache untuk menyimpan hasil pengukuran teks
    private static final Map<String, Float> textWidthCache = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 200;
    
    // Konstanta untuk pengaturan teks
    private static final float MIN_FONT_SIZE = 6f;
    private static final float MAX_SCALE_DOWN = 0.5f; // Maksimal 50% dari ukuran asli
    private static final float LINE_SPACING_MULTIPLIER = 1.2f;
    
    // Menyimpan posisi teks yang sudah dirender untuk deteksi tabrakan
    private static final Map<String, Rect> renderedTextBounds = new ConcurrentHashMap<>();
    
    public static void drawStringIntercepted(Canvas canvas, Paint paint, String str, float x, float y, int anchor) {
        if (str == null || str.trim().isEmpty() || canvas == null || paint == null) return;

        String teksBaru = TranslationManager.processString(str);
        
        // Simpan state asli
        Typeface oldTypeface = paint.getTypeface();
        boolean oldAntiAlias = paint.isAntiAlias();
        boolean oldSubpixel = paint.isSubpixelText();
        float originalTextSize = paint.getTextSize();
        
        try {
            String originalText = str.trim();
            String translatedText = teksBaru.trim();
            
            // Deteksi apakah ini teks Cina
            boolean isChinese = containsChinese(originalText);
            
            // Untuk teks Cina, gunakan font lebih kecil
            float targetFontSize;
            if (isChinese) {
                // Teks Cina ke Indonesia biasanya lebih panjang
                targetFontSize = Math.max(originalTextSize * 0.7f, MIN_FONT_SIZE);
            } else {
                targetFontSize = Math.max(originalTextSize * 0.85f, MIN_FONT_SIZE);
            }
            
            paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            paint.setTextSize(targetFontSize);
            paint.setAntiAlias(true);
            paint.setSubpixelText(true);
            
            // Render dengan smart fitting
            renderSmartText(canvas, paint, originalText, translatedText, x, y, anchor, isChinese);
            
        } finally {
            // Restore state asli
            paint.setTextSize(originalTextSize);
            paint.setTypeface(oldTypeface);
            paint.setAntiAlias(oldAntiAlias);
            paint.setSubpixelText(oldSubpixel);
        }
    }
    
    private static boolean containsChinese(String text) {
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }
    
    private static void renderSmartText(Canvas canvas, Paint paint, String originalText, 
                                        String translatedText, float x, float y, int anchor, boolean isChinese) {
        // Hitung lebar teks
        float originalWidth = getTextWidth(paint, originalText);
        float translatedWidth = getTextWidth(paint, translatedText);
        
        // Hitung posisi awal
        float drawX = alignX(x, originalWidth, anchor);
        float drawY = alignY(y, paint.getFontMetrics(), anchor);
        
        // Cek apakah teks terjemahan lebih panjang
        boolean isLonger = translatedWidth > originalWidth;
        
        if (!isLonger) {
            // Teks terjemahan lebih pendek atau sama, render normal
            drawTextWithShadow(canvas, paint, translatedText, drawX, drawY);
            return;
        }
        
        // Teks terjemahan lebih panjang, perlu penanganan khusus
        float maxWidth = originalWidth;
        
        // Hitung skala yang diperlukan
        float scaleX = maxWidth / translatedWidth;
        
        // Batasi scaling minimum
        if (scaleX < MAX_SCALE_DOWN) {
            scaleX = MAX_SCALE_DOWN;
        }
        
        // Untuk teks Cina, gunakan word wrap jika scaling terlalu ekstrem
        if (isChinese && scaleX < 0.6f) {
            renderWithWordWrap(canvas, paint, translatedText, drawX, drawY, maxWidth);
            return;
        }
        
        // Render dengan scaling
        canvas.save();
        try {
            canvas.translate(drawX, drawY);
            canvas.scale(scaleX, 0.95f); // Sedikit compress vertical
            
            // Render teks
            drawTextWithShadow(canvas, paint, translatedText, 0, 0);
            
            // Simpan bounds untuk deteksi tabrakan
            Rect bounds = new Rect(
                (int)drawX, 
                (int)(drawY - paint.getFontMetrics().ascent),
                (int)(drawX + translatedWidth * scaleX),
                (int)(drawY + paint.getFontMetrics().descent)
            );
            
            String key = translatedText + "_" + drawX + "_" + drawY;
            renderedTextBounds.put(key, bounds);
            
        } finally {
            canvas.restore();
        }
    }
    
    private static void renderWithWordWrap(Canvas canvas, Paint paint, String text, 
                                           float x, float y, float maxWidth) {
        if (text == null || text.isEmpty()) return;
        
        String[] words = text.split("\\s+");
        StringBuilder currentLine = new StringBuilder();
        float lineHeight = (paint.getFontMetrics().descent - paint.getFontMetrics().ascent) * LINE_SPACING_MULTIPLIER;
        float currentY = y;
        
        for (String word : words) {
            String testLine = currentLine.length() > 0 ? currentLine + " " + word : word;
            float testWidth = getTextWidth(paint, testLine);
            
            if (testWidth > maxWidth && currentLine.length() > 0) {
                // Render baris saat ini
                drawTextWithShadow(canvas, paint, currentLine.toString(), x, currentY);
                
                // Mulai baris baru
                currentLine = new StringBuilder(word);
                currentY += lineHeight;
                
                // Cek jika sudah terlalu banyak baris
                if (currentY - y > lineHeight * 3) {
                    // Maksimal 3 baris, truncate jika lebih
                    String truncated = currentLine.toString();
                    if (truncated.length() > 3) {
                        truncated = truncated.substring(0, truncated.length() - 3) + "...";
                    }
                    drawTextWithShadow(canvas, paint, truncated, x, currentY);
                    return;
                }
            } else {
                currentLine = new StringBuilder(testLine);
            }
        }
        
        // Render baris terakhir
        if (currentLine.length() > 0) {
            drawTextWithShadow(canvas, paint, currentLine.toString(), x, currentY);
        }
    }
    
    private static float getTextWidth(Paint paint, String text) {
        // Cek cache
        String cacheKey = text + "_" + paint.getTextSize();
        Float cachedWidth = textWidthCache.get(cacheKey);
        if (cachedWidth != null) {
            return cachedWidth;
        }
        
        // Hitung dan cache
        float width = paint.measureText(text);
        
        // Batasi ukuran cache
        if (textWidthCache.size() < MAX_CACHE_SIZE) {
            textWidthCache.put(cacheKey, width);
        }
        
        return width;
    }
    
    private static void drawTextWithShadow(Canvas canvas, Paint paint, String text, float x, float y) {
        int originalColor = paint.getColor();
        
        // Shadow halus
        paint.setColor(Color.argb(120, 0, 0, 0));
        canvas.drawText(text, x + 1f, y + 1f, paint);
        
        // Text utama
        paint.setColor(originalColor);
        canvas.drawText(text, x, y, paint);
    }
    
    private static float alignX(float x, float width, int anchor) {
        if ((anchor & 1) != 0) return x - (width / 2f); // CENTER
        if ((anchor & 8) != 0) return x - width; // RIGHT
        return x; // LEFT
    }
    
    private static float alignY(float y, Paint.FontMetrics fm, int anchor) {
        if ((anchor & 16) != 0) return y - fm.ascent; // TOP
        if ((anchor & 32) != 0) return y - (fm.ascent + fm.descent) / 2f; // CENTER
        if ((anchor & 64) != 0) return y - fm.descent; // BOTTOM
        return y - fm.ascent; // Default TOP
    }
    
    // Method untuk membersihkan cache
    public static void clearCache() {
        textWidthCache.clear();
        renderedTextBounds.clear();
    }
}
