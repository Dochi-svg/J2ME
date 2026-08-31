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

// ==========================================
// 1. CLASS UTAMA (TranslationManager)
// ==========================================
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
            if (sb.length() == 0) return;
            
            JSONObject json = new JSONObject(sb.toString());
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
            if (sb.length() == 0) return;

            JSONObject json = new JSONObject(sb.toString());
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

    private static String wrapText(String text, int maxCharsPerLine) {
        if (text == null || text.length() <= maxCharsPerLine || text.contains("\n")) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        String[] words = text.split(" ");
        int currentLineLength = 0;

        for (String word : words) {
            if (currentLineLength + word.length() + 1 > maxCharsPerLine) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(word);
                currentLineLength = word.length();
            } else {
                if (sb.length() > 0 && currentLineLength > 0) {
                    sb.append(" ");
                    currentLineLength++;
                }
                sb.append(word);
                currentLineLength += word.length();
            }
        }

        return sb.toString();
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
            
            String translated = translationMap.get(trimmed);
            int maxChar = Math.max(trimmed.length() + 3, 18);
            if (translated.length() > trimmed.length()) {
                translated = wrapText(translated, maxChar);
            }

            return original.replace(trimmed, translated);
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

        if (isDumpMode) {
            dumpedStrings.keySet().removeIf(key -> trimmed.length() > key.length() && trimmed.contains(key));

            boolean isSubText = false;
            for (String key : dumpedStrings.keySet()) {
                if (key.length() >= trimmed.length() && key.contains(trimmed)) {
                    isSubText = true;
                    break;
                }
            }

            if (!isSubText && !dumpedStrings.containsKey(trimmed)) {
                dumpedStrings.put(trimmed, trimmed);
                hasNewDataToSave.set(true);
            }
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
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }

                    JSONArray jsonArray = new JSONArray(response.toString());
                    if (jsonArray.length() > 0) {
                        JSONArray sentences = jsonArray.getJSONArray(0);
                        StringBuilder translatedResult = new StringBuilder();

                        for (int i = 0; i < sentences.length(); i++) {
                            JSONArray sentence = sentences.getJSONArray(i);
                            translatedResult.append(sentence.getString(0));
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
            // Error koneksi diabaikan
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

// ==========================================
// 2. CLASS GRAPHICS UTILS (VERSI ANDROID NATIVE CANVAS/PAINT)
// ==========================================
class GraphicsUtils {

    /**
     * Menggambar string pada Android Canvas dengan Auto-Scaling, Text Shadow, Arial Bold, dan Line Height.
     * Fitur baru: Auto Font Size Reduction & Intelligent Text Wrapping
     */
    public static void drawStringIntercepted(Canvas canvas, Paint paint, String str, float x, float y, int anchor) {
        if (str == null || str.trim().isEmpty() || canvas == null || paint == null) return;

        String teksBaru = TranslationManager.processString(str);

        // Backup state paint asli
        Typeface oldTypeface = paint.getTypeface();
        boolean oldAntiAlias = paint.isAntiAlias();
        boolean oldSubpixel = paint.isSubpixelText();
        float originalTextSize = paint.getTextSize() > 0 ? paint.getTextSize() : 13f;

        // Terapkan Arial Bold & Anti-Aliasing
        paint.setTypeface(Typeface.create("Arial", Typeface.BOLD));
        paint.setAntiAlias(true);
        paint.setSubpixelText(true);

        try {
            float widthAsli = paint.measureText(str.trim());
            
            // Hitung tinggi bounding box (estimasi)
            Paint.FontMetrics fm = paint.getFontMetrics();
            float lineHeight = (fm.descent - fm.ascent) * 1.6f;

            // Wrap text berdasarkan panjang teks asli
            String wrappedText = wrapTextIntelligent(teksBaru, paint, widthAsli);
            String[] baris = wrappedText.split("\n");

            // Hitung ukuran yang dibutuhkan
            float maxLineWidth = 0;
            for (String line : baris) {
                float lineWidth = paint.measureText(line);
                if (lineWidth > maxLineWidth) {
                    maxLineWidth = lineWidth;
                }
            }

            // Jika text terlalu lebar, kurangi font size secara progresif
            float fontSize = originalTextSize;
            int attemptCount = 0;
            while (maxLineWidth > widthAsli && fontSize > 4 && attemptCount < 10) {
                fontSize -= 0.5f;
                paint.setTextSize(fontSize);
                
                maxLineWidth = 0;
                for (String line : baris) {
                    float lineWidth = paint.measureText(line);
                    if (lineWidth > maxLineWidth) {
                        maxLineWidth = lineWidth;
                    }
                }
                attemptCount++;
            }

            // Gambar setiap baris
            if (baris.length > 0) {
                Paint.FontMetrics fmAdjusted = paint.getFontMetrics();
                float adjustedLineHeight = (fmAdjusted.descent - fmAdjusted.ascent) * 1.6f;

                for (int i = 0; i < baris.length; i++) {
                    float nextY = y + (i * adjustedLineHeight);
                    renderAndScaleText(canvas, paint, str.trim(), baris[i], x, nextY, anchor);
                }
            }

        } finally {
            // Restore state paint bawaan
            paint.setTextSize(originalTextSize);
            paint.setTypeface(oldTypeface);
            paint.setAntiAlias(oldAntiAlias);
            paint.setSubpixelText(oldSubpixel);
        }
    }

    /**
     * Wrap text secara intelligent berdasarkan lebar maksimal dari teks asli
     */
    private static String wrapTextIntelligent(String text, Paint paint, float maxWidth) {
        if (text == null || maxWidth <= 0) {
            return text;
        }

        // Jika sudah ada newline, jangan modifikasi
        if (text.contains("\n")) {
            return text;
        }

        String[] words = text.split(" ");
        if (words.length <= 1) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String testLine = currentLine.length() == 0 
                ? word 
                : currentLine.toString() + " " + word;

            float lineWidth = paint.measureText(testLine);

            if (lineWidth <= maxWidth) {
                if (currentLine.length() > 0) {
                    currentLine.append(" ");
                }
                currentLine.append(word);
            } else {
                // Line akan terlalu panjang
                if (currentLine.length() > 0) {
                    result.append(currentLine.toString()).append("\n");
                }
                currentLine = new StringBuilder(word);
            }
        }

        // Tambahkan baris terakhir
        if (currentLine.length() > 0) {
            result.append(currentLine.toString());
        }

        return result.toString();
    }

    private static void renderAndScaleText(Canvas canvas, Paint paint, String teksAsli, String teksRender, float x, float y, int anchor) {
        float widthAsli = paint.measureText(teksAsli);
        float widthBaru = paint.measureText(teksRender);

        Paint.FontMetrics fm = paint.getFontMetrics();

        float drawX = alignX(x, widthAsli, anchor);
        float drawY = alignY(y, fm, anchor);

        // Hanya scale jika benar-benar diperlukan dan tidak akan melebihi lebar asli
        boolean butuhScaling = !teksRender.equals(teksAsli) && (widthBaru > widthAsli) && (widthBaru > 0) && (widthAsli > 0);

        canvas.save();
        try {
            if (butuhScaling) {
                float scaleX = widthAsli / widthBaru;
                
                // Jika scale factor terlalu kecil, gunakan font size reduction instead
                if (scaleX < 0.6f) {
                    // Font sudah dikurangi di drawStringIntercepted, jadi jangan scale lagi
                    drawShadowAndText(canvas, paint, teksRender, drawX, drawY);
                } else {
                    canvas.translate(drawX, drawY);
                    canvas.scale(scaleX, 1.0f);
                    drawShadowAndText(canvas, paint, teksRender, 0, 0);
                }
            } else {
                drawShadowAndText(canvas, paint, teksRender, drawX, drawY);
            }
        } finally {
            canvas.restore();
        }
    }

    private static void drawShadowAndText(Canvas canvas, Paint paint, String text, float x, float y) {
        int originalColor = paint.getColor();

        // 1. Text Shadow Hitam Transparan: 1px 1px offset
        paint.setColor(Color.argb(140, 0, 0, 0));
        canvas.drawText(text, x + 1f, y + 1f, paint);

        // 2. Teks Utama
        paint.setColor(originalColor);
        canvas.drawText(text, x, y, paint);
    }

    private static float alignX(float x, float width, int anchor) {
        if ((anchor & 1) != 0) return x - (width / 2f); // HCENTER
        if ((anchor & 8) != 0) return x - width;         // RIGHT
        return x;                                        // LEFT
    }

    private static float alignY(float y, Paint.FontMetrics fm, int anchor) {
        if ((anchor & 16) != 0) return y - fm.ascent;                  // TOP
        if ((anchor & 32) != 0) return y - (fm.ascent + fm.descent)/2f; // VCENTER
        if ((anchor & 64) != 0) return y - fm.descent;                 // BOTTOM
        return y;                                                      // Baseline default
    }
        }
