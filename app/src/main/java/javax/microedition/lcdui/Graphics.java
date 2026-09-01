/*
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2017-2018 Nikita Shakarun
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.lcdui;

import static javax.microedition.lcdui.game.Sprite.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Build;
import android.util.Log;

import com.mascotcapsule.micro3d.v3.Graphics3D;
import ru.playsoftware.j2meloader.util.TranslationManager;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

public class Graphics implements
		com.vodafone.v10.graphics.j3d.Graphics3D,
		com.motorola.graphics.j3d.Graphics3D,
		com.jblend.graphics.j3d.Graphics3D {
	
	public static final int HCENTER = 1;
	public static final int VCENTER = 2;
	public static final int LEFT = 4;
	public static final int RIGHT = 8;
	public static final int TOP = 16;
	public static final int BOTTOM = 32;
	public static final int BASELINE = 64;

	public static final int SOLID = 0;
	public static final int DOTTED = 1;

	// Instance static tracker untuk akses mudah dari MicroActivity
	private static Graphics currentInstance;

	private final Canvas canvas;
	private final Image image;

	private final Paint drawPaint = new Paint();
	private final Paint fillPaint = new Paint();

	private int translateX;
	private int translateY;

	private final Rect clip = new Rect();
	private final Rect rect = new Rect();
	private final RectF rectF = new RectF();
	private final Path path = new Path();

	private final DashPathEffect dashPathEffect = new DashPathEffect(new float[]{5, 5}, 0);
	private int stroke = SOLID;

	private Font font = Font.getDefaultFont();
	private com.mascotcapsule.micro3d.v3.Graphics3D g3d;

	// ===== TEXT DUMPING & TRANSLATION FEATURE =====
	private boolean textDumpEnabled = true;
	private final Map<String, String> translations = new HashMap<>();
	private final Set<String> dumpedTexts = new HashSet<>();
	
	// ===== TEXT COMPRESSION SETTINGS =====
	private boolean autoCompressText = true;
	private float maxCompressionRatio = 0.5f;  // Maksimal kompresi 50% (jangan terlalu pipih)
	private float minReadableTextSize = 8f;    // Ukuran minimum yang masih readable
	private boolean trimExtraSpacesEnabled = true;
	private float canvasWidthFallback = 0.85f;  // 85% dari lebar canvas jika tidak ada clip

	Graphics(Image image) {
		this.image = image;
		canvas = new Canvas(image.getBitmap());
		canvas.save();
		canvas.clipRect(image.getBounds());
		canvas.getClipBounds(clip);
		drawPaint.setStyle(Paint.Style.STROKE);
		fillPaint.setStyle(Paint.Style.FILL);
		drawPaint.setAntiAlias(false);
		fillPaint.setAntiAlias(false);

		currentInstance = this;
	}

	public static Graphics getCurrentInstance() {
		return currentInstance;
	}

	public void reset(float cl, float ct, float cr, float cb) {
		setColor(0);
		setFont(Font.getDefaultFont());
		setStrokeStyle(SOLID);
		canvas.restoreToCount(1);
		canvas.save();
		canvas.clipRect(cl, ct, cr, cb);
		canvas.getClipBounds(this.clip);
		translateX = 0;
		translateY = 0;
	}

	public Canvas getCanvas() {
		return canvas;
	}

	public void fillPolygon(int[] xPoints, int xOffset, int[] yPoints, int yOffset, int nPoints) {
		if (nPoints > 0) {
			Path path = computePath(xPoints, xOffset, yPoints, yOffset, nPoints);
			canvas.drawPath(path, fillPaint);
		}
	}

	public void drawPolygon(int[] xPoints, int xOffset, int[] yPoints, int yOffset, int nPoints) {
		if (nPoints > 0) {
			Path path = computePath(xPoints, xOffset, yPoints, yOffset, nPoints);
			canvas.drawPath(path, drawPaint);
		}
	}

	private Path computePath(int[] xPoints, int xOffset, int[] yPoints, int yOffset, int nPoints) {
		path.reset();
		path.moveTo((float) xPoints[xOffset], (float) yPoints[yOffset]);
		for (int i = 1; i < nPoints; i++) {
			path.lineTo((float) xPoints[xOffset + i], (float) yPoints[yOffset + i]);
		}
		path.close();
		return path;
	}

	public void setColor(int color) {
		setColorAlpha(color | 0xFF000000);
	}

	public void setColorAlpha(int color) {
		drawPaint.setColor(color);
		fillPaint.setColor(color);
	}

	public void setColor(int r, int g, int b) {
		drawPaint.setARGB(255, r, g, b);
		fillPaint.setARGB(255, r, g, b);
	}

	public void setGrayScale(int value) {
		setColor(value, value, value);
	}

	public int getGrayScale() {
		int argb = drawPaint.getColor();
		int r = argb >> 16 & 0xFF;
		int g = argb >> 8 & 0xFF;
		int b = argb & 0xFF;
		return 0x4CB2 * r + 0x9691 * g + 0x1D3E * b >> 16;
	}

	public int getRedComponent() {
		return (drawPaint.getColor() >> 16) & 0xFF;
	}

	public int getGreenComponent() {
		return (drawPaint.getColor() >> 8) & 0xFF;
	}

	public int getBlueComponent() {
		return drawPaint.getColor() & 0xFF;
	}

	public int getColor() {
		return drawPaint.getColor();
	}

	public int getDisplayColor(int color) {
		return color;
	}

	public void setStrokeStyle(int stroke) {
		this.stroke = stroke;

		if (stroke == DOTTED) {
			drawPaint.setPathEffect(dashPathEffect);
		} else {
			drawPaint.setPathEffect(null);
		}
	}

	public int getStrokeStyle() {
		return stroke;
	}

	public void setFont(Font font) {
		this.font = font == null ? Font.getDefaultFont() : font;
	}

	public Font getFont() {
		return font;
	}

	public void setClip(int x, int y, int width, int height) {
		clip.set(x, y, x + width, y + height);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			canvas.restore();
			canvas.save();
			canvas.translate(translateX, translateY);
			canvas.clipRect(clip);
		} else {
			canvas.clipRect(clip, Region.Op.REPLACE);
		}
		canvas.getClipBounds(clip);
	}

	public void clipRect(int x, int y, int width, int height) {
		clip.set(x, y, x + width, y + height);
		canvas.clipRect(clip);
		canvas.getClipBounds(clip);
	}

	public int getClipX() {
		return clip.left;
	}

	public int getClipY() {
		return clip.top;
	}

	public int getClipWidth() {
		return clip.width();
	}

	public int getClipHeight() {
		return clip.height();
	}

	public void translate(int dx, int dy) {
		translateX += dx;
		translateY += dy;

		canvas.translate(dx, dy);
		canvas.getClipBounds(clip);
	}

	public int getTranslateX() {
		return translateX;
	}

	public int getTranslateY() {
		return translateY;
	}

	public void drawLine(int x1, int y1, int x2, int y2) {
		if (x2 >= x1) {
			x2++;
		} else {
			x1++;
		}

		if (y2 >= y1) {
			y2++;
		} else {
			y1++;
		}

		canvas.drawLine(x1, y1, x2, y2, drawPaint);
	}

	public void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
		if (width < 0 || height < 0) return;
		rectF.set(x, y, x + width, y + height);
		canvas.drawArc(rectF, -startAngle, -arcAngle, false, drawPaint);
	}

	public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
		if (width <= 0 || height <= 0) return;
		rectF.set(x, y, x + width, y + height);
		canvas.drawArc(rectF, -startAngle, -arcAngle, true, fillPaint);
	}

	public void drawRect(int x, int y, int width, int height) {
		if (width < 0 || height < 0) return;
		canvas.drawRect(x, y, x + width, y + height, drawPaint);
	}

	public void fillRect(int x, int y, int width, int height) {
		if (width <= 0 || height <= 0) return;
		canvas.drawRect(x, y, x + width, y + height, fillPaint);
	}

	public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
		if (width < 0 || height < 0) return;
		rectF.set(x, y, x + width, y + height);
		canvas.drawRoundRect(rectF, arcWidth * 0.5f, arcHeight * 0.5f, drawPaint);
	}

	public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
		if (width < 0 || height < 0) return;
		rectF.set(x, y, x + width, y + height);
		canvas.drawRoundRect(rectF, arcWidth * 0.5f, arcHeight * 0.5f, fillPaint);
	}

	public void fillTriangle(int x1, int y1, int x2, int y2, int x3, int y3) {
		fillPolygon(new int[]{x1, x2, x3}, 0, new int[]{y1, y2, y3}, 0, 3);
	}

	public void drawChar(char character, int x, int y, int anchor) {
		drawChars(new char[]{character}, 0, 1, x, y, anchor);
	}

	public void drawChars(char[] data, int offset, int length, int x, int y, int anchor) {
		String originalText = new String(data, offset, length);
		String textToDraw = originalText;

		if (translations.containsKey(originalText)) {
			textToDraw = translations.get(originalText);
		}

		// Trim spasi berlebih
		if (trimExtraSpacesEnabled) {
			textToDraw = trimExtraSpaces(textToDraw);
		}

		Paint paint = font.paint;
		if ((anchor & Graphics.RIGHT) != 0) {
			paint.setTextAlign(Paint.Align.RIGHT);
		} else if ((anchor & Graphics.HCENTER) != 0) {
			paint.setTextAlign(Paint.Align.CENTER);
		} else {
			paint.setTextAlign(Paint.Align.LEFT);
		}

		float ly;
		if ((anchor & Graphics.BOTTOM) != 0) {
			ly = y - font.descent;
		} else if ((anchor & Graphics.VCENTER) != 0) {
			ly = y - (font.descent + font.ascent) / 2.0f;
		} else if ((anchor & Graphics.BASELINE) != 0) {
			ly = y;
		} else {
			ly = y - font.ascent;
		}

		paint.setColor(fillPaint.getColor());
		
		// Gambar dengan kompresi jika perlu
		if (autoCompressText) {
			drawCompressedText(textToDraw, x, ly, paint);
		} else {
			canvas.drawText(textToDraw, x, ly, paint);
		}

		if (textDumpEnabled) {
			if (!translations.containsKey(originalText) && !dumpedTexts.contains(originalText)) {
				dumpedTexts.add(originalText);
			}
		}
	}

	public void drawString(String text, int x, int y, int anchor) {
		if (text == null) return;
		String originalText = text;
		
		if (translations.containsKey(originalText)) {
			text = translations.get(originalText);
		} else {
			text = TranslationManager.processString(text);
		}

		// Trim spasi berlebih
		if (trimExtraSpacesEnabled) {
			text = trimExtraSpaces(text);
		}

		Paint paint = font.paint;
		if ((anchor & Graphics.RIGHT) != 0) {
			paint.setTextAlign(Paint.Align.RIGHT);
		} else if ((anchor & Graphics.HCENTER) != 0) {
			paint.setTextAlign(Paint.Align.CENTER);
		} else {
			paint.setTextAlign(Paint.Align.LEFT);
		}

		float ly;
		if ((anchor & Graphics.BOTTOM) != 0) {
			ly = y - font.descent;
		} else if ((anchor & Graphics.VCENTER) != 0) {
			ly = y - (font.descent + font.ascent) / 2.0f;
		} else if ((anchor & Graphics.BASELINE) != 0) {
			ly = y;
		} else {
			ly = y - font.ascent;
		}

		paint.setColor(fillPaint.getColor());
		
		// Gambar dengan kompresi jika perlu
		if (autoCompressText) {
			drawCompressedText(text, x, ly, paint);
		} else {
			canvas.drawText(text, x, ly, paint);
		}

		if (textDumpEnabled) {
			if (!translations.containsKey(originalText) && !dumpedTexts.contains(originalText)) {
				dumpedTexts.add(originalText);
			}
		}
	}

	// ===== TEXT COMPRESSION METHODS =====
	
	/**
	 * Trim spasi berlebih dari teks
	 */
	private String trimExtraSpaces(String text) {
		if (text == null || text.isEmpty()) return text;
		
		// Trim awal dan akhir
		text = text.trim();
		
		// Ganti multiple spaces dengan single space
		text = text.replaceAll("\\s+", " ");
		
		// Hapus spasi sebelum tanda baca
		text = text.replaceAll("\\s+([,.!?;:])", "$1");
		
		return text;
	}
	
	/**
	 * Gambar teks dengan kompresi adaptif
	 */
	private void drawCompressedText(String text, float x, float y, Paint paint) {
		if (text == null || text.isEmpty()) return;
		
		float maxWidth = getAvailableWidth();
		float textWidth = paint.measureText(text);
		
		// Jika teks sudah muat, gambar normal
		if (textWidth <= maxWidth) {
			canvas.drawText(text, x, y, paint);
			return;
		}
		
		// Simpan state asli
		float originalTextSize = paint.getTextSize();
		float originalScaleX = paint.getTextScaleX();
		
		// ==== LEVEL 1: Horizontal Compression (squish) ====
		float scaleX = maxWidth / textWidth;
		
		if (scaleX >= maxCompressionRatio) {
			// Kompresi masih dalam batas wajar
			paint.setTextScaleX(scaleX);
			canvas.drawText(text, x, y, paint);
			
			// Reset
			paint.setTextScaleX(originalScaleX);
			return;
		}
		
		// ==== LEVEL 2: Kombinasi scale down + squish ====
		// Kecilkan ukuran font secara bertahap
		float newTextSize = originalTextSize;
		float finalScaleX = scaleX;
		
		while (finalScaleX < maxCompressionRatio && newTextSize > minReadableTextSize) {
			// Kurangi ukuran font 5%
			newTextSize *= 0.95f;
			paint.setTextSize(newTextSize);
			
			// Hitung ulang
			textWidth = paint.measureText(text);
			finalScaleX = maxWidth / textWidth;
		}
		
		// Terapkan kompresi final
		finalScaleX = Math.max(finalScaleX, maxCompressionRatio);
		paint.setTextScaleX(finalScaleX);
		canvas.drawText(text, x, y, paint);
		
		// Reset semua
		paint.setTextSize(originalTextSize);
		paint.setTextScaleX(originalScaleX);
	}
	
	/**
	 * Dapatkan lebar yang tersedia untuk menggambar teks
	 */
	private float getAvailableWidth() {
		// Cek clip region terlebih dahulu
		if (clip.width() > 0 && clip.width() < canvas.getWidth()) {
			return clip.width();
		}
		
		// Fallback ke canvas width
		if (canvas.getWidth() > 0) {
			return canvas.getWidth() * canvasWidthFallback;
		}
		
		// Default
		return 150;
	}
	
	/**
	 * Set pengaturan kompresi teks
	 */
	public void setTextCompressionSettings(boolean enabled, float maxRatio, float minSize) {
		this.autoCompressText = enabled;
		this.maxCompressionRatio = Math.max(0.3f, Math.min(0.9f, maxRatio));
		this.minReadableTextSize = Math.max(4f, minSize);
	}
	
	/**
	 * Enable/disable auto kompresi teks
	 */
	public void setAutoCompressText(boolean enabled) {
		this.autoCompressText = enabled;
	}
	
	/**
	 * Set rasio kompresi maksimal (0.3 - 0.9)
	 */
	public void setMaxCompressionRatio(float ratio) {
		this.maxCompressionRatio = Math.max(0.3f, Math.min(0.9f, ratio));
	}
	
	/**
	 * Set ukuran font minimum yang masih readable
	 */
	public void setMinReadableTextSize(float size) {
		this.minReadableTextSize = Math.max(4f, size);
	}
	
	/**
	 * Enable/disable trim spasi berlebih
	 */
	public void setTrimExtraSpaces(boolean enabled) {
		this.trimExtraSpacesEnabled = enabled;
	}
	
	/**
	 * Set fallback width sebagai persentase dari canvas (0.5 - 1.0)
	 */
	public void setCanvasWidthFallback(float percentage) {
		this.canvasWidthFallback = Math.max(0.5f, Math.min(1.0f, percentage));
	}

	public void drawImage(Image image, int x, int y, int anchor) {
		float lx;
		if ((anchor & Graphics.RIGHT) != 0) {
			lx = x - image.getWidth();
		} else if ((anchor & Graphics.HCENTER) != 0) {
			lx = x - image.getWidth() / 2.0f;
		} else {
			lx = x;
		}

		float ly;
		if ((anchor & Graphics.BOTTOM) != 0) {
			ly = y - image.getHeight();
		} else if ((anchor & Graphics.VCENTER) != 0) {
			ly = y - image.getHeight() / 2.0f;
		} else {
			ly = y;
		}

		canvas.drawBitmap(image.getBitmap(), lx, ly, null);
	}

	public void drawSubstring(String str, int offset, int len, int x, int y, int anchor) {
		if (str == null) return;
		String sub = str.substring(offset, offset + len);
		drawString(sub, x, y, anchor);
	}

	public void drawRegion(Image image, int x_src, int y_src, int width, int height,
						   int transform, int x_dst, int y_dst, int anchor) {
		if (width <= 0 || height <= 0) return;

		Rect srcR = rect;
		RectF dstR = rectF;
		float dx;
		float dy;
		srcR.set(x_src, y_src, x_src + width, y_src + height);

		Matrix matrix = new Matrix();
		switch (transform) {
			case TRANS_NONE: {
				if ((anchor & Graphics.RIGHT) != 0) {
					dx = x_dst - width;
				} else if ((anchor & Graphics.HCENTER) != 0) {
					dx = x_dst - width / 2.0f;
				} else {
					dx = x_dst;
				}
				if ((anchor & Graphics.BOTTOM) != 0) {
					dy = y_dst - height;
				} else if ((anchor & Graphics.VCENTER) != 0) {
					dy = y_dst - height / 2.0f;
				} else {
					dy = y_dst;
				}

				dstR.set(dx, dy, dx + width, dy + height);
				canvas.drawBitmap(image.getBitmap(), srcR, dstR, null);
				return;
			}
			case TRANS_ROT90:
				matrix.preRotate(90);
				break;
			case TRANS_ROT180:
				matrix.preRotate(180);
				break;
			case TRANS_ROT270:
				matrix.preRotate(270);
				break;
			case TRANS_MIRROR:
				matrix.preScale(-1, 1);
				break;
			case TRANS_MIRROR_ROT90:
				matrix.preRotate(90);
				matrix.preScale(-1, 1);
				break;
			case TRANS_MIRROR_ROT180:
				matrix.preRotate(180);
				matrix.preScale(-1, 1);
				break;
			case TRANS_MIRROR_ROT270:
				matrix.preRotate(270);
				matrix.preScale(-1, 1);
				break;
			default:
				throw new IllegalArgumentException("Illegal transform=" + transform);
		}

		dstR.set(0, 0, width, height);
		matrix.mapRect(dstR);

		if ((anchor & Graphics.RIGHT) != 0) {
			dx = x_dst - dstR.width();
		} else if ((anchor & Graphics.HCENTER) != 0) {
			dx = x_dst - dstR.width() / 2.0f;
		} else {
			dx = x_dst;
		}
		if ((anchor & Graphics.BOTTOM) != 0) {
			dy = y_dst - dstR.height();
		} else if ((anchor & Graphics.VCENTER) != 0) {
			dy = y_dst - dstR.height() / 2.0f;
		} else {
			dy = y_dst;
		}

		matrix.postTranslate(Math.round(dx - dstR.left), Math.round(dy - dstR.top));
		dstR.set(0, 0, width, height);

		canvas.save();
		canvas.concat(matrix);
		try {
			canvas.drawBitmap(image.getBitmap(), srcR, dstR, null);
		} finally {
			canvas.restore();
		}
	}

	public void drawRGB(int[] rgbData, int offset, int scanlength, int x, int y, int width, int height, boolean processAlpha) {
		if (rgbData == null) {
			throw new NullPointerException();
		}
		if (width == 0 || height == 0) {
			return;
		}
		if (offset < 0 || offset > rgbData.length) {
			throw new ArrayIndexOutOfBoundsException();
		}
		if (width < 0 || height < 0) {
			Log.w(getClass().getSimpleName(), "drawRGB: width=" + width + ", height=" + height);
		}
		if (scanlength > 0) {
			if (offset + scanlength * (height - 1) + width > rgbData.length) {
				throw new ArrayIndexOutOfBoundsException();
			}
		} else {
			if (offset + width > rgbData.length || offset + scanlength * (height - 1) < 0) {
				throw new ArrayIndexOutOfBoundsException();
			}
		}

		int[] pixels = new int[height * width];
		int alphaCorrection = processAlpha ? Color.TRANSPARENT : Color.BLACK;
		for (int i = 0; i < height; i++) {
			int s = offset + i * scanlength;
			int d = i * width;
			for (int j = 0; j < width; j++) {
				int pixel = rgbData[s++];
				pixels[d + j] = alphaCorrection | pixel;
			}
		}
		canvas.drawBitmap(pixels, 0, width, x, y, width, height, true, null);
	}

	public void copyArea(int x_src, int y_src, int width, int height,
						 int x_dest, int y_dest, int anchor) {
		if (width <= 0 || height <= 0) return;
		final int[] pixels = new int[width * height];
		image.getBitmap().getPixels(pixels, 0, width, x_src, y_src, width, height);
		float dx;
		if ((anchor & Graphics.RIGHT) != 0) {
			dx = x_dest - width;
		} else if ((anchor & Graphics.HCENTER) != 0) {
			dx = x_dest - width / 2.0f;
		} else {
			dx = x_dest;
		}
		float dy;
		if ((anchor & Graphics.BOTTOM) != 0) {
			dy = y_dest - height;
		} else if ((anchor & Graphics.VCENTER) != 0) {
			dy = y_dest - height / 2.0f;
		} else {
			dy = y_dest;
		}
		canvas.drawBitmap(pixels, 0, width, dx, dy, width, height, false, null);
	}

	public void getPixels(int[] pixels, int offset, int stride,
						  int x, int y, int width, int height) {
		Bitmap b = image.getBitmap();
		int w = Math.min(width, b.getWidth() - x);
		int h = Math.min(height, b.getHeight() - y);
		b.getPixels(pixels, offset, stride, x, y, w, h);
	}

	public Bitmap getBitmap() {
		return image.getBitmap();
	}

	void flush(Image image, int x, int y, int width, int height) {
		rect.set(x, y, x + width, y + height);
		canvas.drawBitmap(image.getBitmap(), rect, rect, null);
	}

	@Override
	public synchronized void drawFigure(com.vodafone.v10.graphics.j3d.Figure figure,
										int x, int y,
										com.vodafone.v10.graphics.j3d.FigureLayout layout,
										com.vodafone.v10.graphics.j3d.Effect3D effect) {
		if (g3d == null) g3d = new Graphics3D();
		g3d.bind(this);
		g3d.drawFigure(figure, x, y, layout, effect);
		g3d.release(this);
	}

	@Override
	public synchronized void drawFigure(com.motorola.graphics.j3d.Figure figure,
										int x, int y,
										com.motorola.graphics.j3d.FigureLayout layout,
										com.motorola.graphics.j3d.Effect3D effect) {
		if (g3d == null) g3d = new Graphics3D();
		g3d.bind(this);
		g3d.drawFigure(figure, x, y, layout, effect);
		g3d.release(this);
	}

	@Override
	public synchronized void drawFigure(com.jblend.graphics.j3d.Figure figure,
										int x, int y,
										com.jblend.graphics.j3d.FigureLayout layout,
										com.jblend.graphics.j3d.Effect3D effect) {
		if (g3d == null) g3d = new Graphics3D();
		g3d.bind(this);
		g3d.drawFigure(figure, x, y, layout, effect);
		g3d.release(this);
	}

	// ===== TRANSLATION LOAD & DUMP ENGINE =====

	public void loadTranslationsFromFile(File translationFile) {
		translations.clear();
		if (translationFile == null || !translationFile.exists()) return;

		try {
			String jsonContent = readFileAsString(translationFile);
			JSONObject jsonObj = new JSONObject(jsonContent);
			JSONObject translationsObj = jsonObj.optJSONObject("translations");
			
			if (translationsObj != null) {
				java.util.Iterator<String> keys = translationsObj.keys();
				while (keys.hasNext()) {
					String key = keys.next();
					translations.put(key, translationsObj.getString(key));
				}
				Log.i("Graphics", "Loaded " + translations.size() + " translations.");
			}
		} catch (Exception e) {
			Log.e("Graphics", "Error loading translations", e);
		}
	}

	public void saveDumpToJSON(File outputFile) {
		if (dumpedTexts.isEmpty()) return;

		try {
			File parentDir = outputFile.getParentFile();
			if (parentDir != null && !parentDir.exists()) {
				parentDir.mkdirs();
			}

			JSONObject root = new JSONObject();
			root.put("version", "1.0");
			root.put("timestamp", System.currentTimeMillis());

			JSONObject textsObj = new JSONObject();
			for (String text : dumpedTexts) {
				textsObj.put(text, "");
			}
			root.put("texts", textsObj);

			try (FileWriter writer = new FileWriter(outputFile)) {
				writer.write(root.toString(2));
			}

			Log.i("Graphics", "✅ dump.json saved to: " + outputFile.getAbsolutePath());
		} catch (Exception e) {
			Log.e("Graphics", "Error saving dump.json", e);
		}
	}

	private String readFileAsString(File file) throws IOException {
		StringBuilder sb = new StringBuilder();
		try (FileReader reader = new FileReader(file)) {
			char[] buffer = new char[1024];
			int length;
			while ((length = reader.read(buffer)) > 0) {
				sb.append(buffer, 0, length);
			}
		}
		return sb.toString();
	}
}
