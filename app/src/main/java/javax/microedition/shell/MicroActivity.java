package javax.microedition.shell;

import static ru.playsoftware.j2meloader.util.Constants.*;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.DigitsKeyListener;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.preference.PreferenceManager;

import org.acra.ACRA;
import org.acra.ErrorReporter;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;

import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.ViewHandler;
import javax.microedition.lcdui.event.SimpleEvent;
import javax.microedition.lcdui.keyboard.VirtualKeyboard;
import javax.microedition.location.LocationProviderImpl;
import javax.microedition.util.ContextHolder;

import io.reactivex.SingleObserver;
import io.reactivex.disposables.Disposable;
import ru.playsoftware.j2meloader.BuildConfig;
import ru.playsoftware.j2meloader.JLMemoryDebugService;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.databinding.ActivityMicroBinding;
import ru.playsoftware.j2meloader.util.Constants;
import ru.playsoftware.j2meloader.util.LogUtils;

public class MicroActivity extends AppCompatActivity {
	private static final int ORIENTATION_DEFAULT = 0;
	private static final int ORIENTATION_AUTO = 1;
	private static final int ORIENTATION_PORTRAIT = 2;
	private static final int ORIENTATION_LANDSCAPE = 3;

	private Displayable current;
	private boolean visible;
	private boolean actionBarEnabled;
	private boolean statusBarEnabled;
	private MicroLoader microLoader;
	private String appName;
	private InputMethodManager inputMethodManager;
	private int menuKey;
	private String appPath;

	// Penampung buffer tombol untuk kombinasi #0#
	private final StringBuilder keySequence = new StringBuilder();

	public ActivityMicroBinding binding;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		lockNightMode();
		super.onCreate(savedInstanceState);
		ContextHolder.setCurrentActivity(this);

		binding = ActivityMicroBinding.inflate(getLayoutInflater());
		View view = binding.getRoot();
		setContentView(view);
		setSupportActionBar(binding.toolbar);

		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		actionBarEnabled = sp.getBoolean(PREF_TOOLBAR, false);
		statusBarEnabled = sp.getBoolean(PREF_STATUSBAR, false);
		if (sp.getBoolean(PREF_ADD_CUTOUT_AREA, false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			getWindow().getAttributes().layoutInDisplayCutoutMode =
					WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
		}
		if (sp.getBoolean(PREF_KEEP_SCREEN, false)) {
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
		ContextHolder.setVibration(sp.getBoolean(PREF_VIBRATION, true));
		Canvas.setScreenshotRawMode(sp.getBoolean(PREF_SCREENSHOT_SWITCH, false));
		Intent intent = getIntent();
		if (BuildConfig.FULL_EMULATOR) {
			appName = intent.getStringExtra(KEY_MIDLET_NAME);
			Uri data = intent.getData();
			if (data == null) {
				showErrorDialog("Invalid intent: app path is null");
				return;
			}
			appPath = data.toString();
		} else {
			appName = getTitle().toString();
			appPath = getApplicationInfo().dataDir + "/files/converted/midlet";
			File dir = new File(appPath);
			if (!dir.exists() && !dir.mkdirs()) {
				throw new RuntimeException("Can't access file system");
			}
		}
		String arguments = intent.getStringExtra(KEY_START_ARGUMENTS);
		if (arguments != null) {
			MidletSystem.setProperty("com.nokia.mid.cmdline", arguments);
			String[] arr = arguments.split(";");
			for (String s: arr) {
				if (s.length() == 0) {
					continue;
				}
				if (s.contains("=")) {
					int i = s.indexOf('=');
					String k = s.substring(0, i);
					String v = s.substring(i + 1);
					MidletSystem.setProperty(k, v);
				} else {
					MidletSystem.setProperty(s, "");
				}
			}
		}
		MidletSystem.setProperty("com.nokia.mid.cmdline.instance", "1");
		microLoader = new MicroLoader(this, appPath);
		if (!microLoader.init()) {
			Config.startApp(this, appName, appPath, true, arguments);
			finish();
			return;
		}
		microLoader.applyConfiguration();
		VirtualKeyboard vk = ContextHolder.getVk();
		int orientation = microLoader.getOrientation();
		if (vk != null) {
			vk.setView(binding.overlayView);
			binding.overlayView.addLayer(vk);
			if (vk.isPhone()) {
				orientation = ORIENTATION_PORTRAIT;
			}
		}
		setOrientation(orientation);
		menuKey = microLoader.getMenuKeyCode();
		inputMethodManager = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

		try {
			loadMIDlet();
		} catch (Exception e) {
			e.printStackTrace();
			showErrorDialog(e.toString());
		}
	}

	public void lockNightMode() {
		int current = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
		if (current == Configuration.UI_MODE_NIGHT_YES) {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
		} else {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		visible = true;
		MidletThread.resumeApp();
	}

	@Override
	public void onPause() {
		visible = false;
		hideSoftInput();
		MidletThread.pauseApp();
		
		saveDumpAutomatically();
		
		super.onPause();
	}

	private void hideSoftInput() {
		if (inputMethodManager != null) {
			IBinder windowToken = binding.displayableContainer.getWindowToken();
			inputMethodManager.hideSoftInputFromWindow(windowToken, 0);
		}
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
				current instanceof Canvas) {
			hideSystemUI();
		}
	}

	@SuppressLint("SourceLockedOrientationActivity")
	private void setOrientation(int orientation) {
		switch (orientation) {
			case ORIENTATION_AUTO:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
				break;
			case ORIENTATION_PORTRAIT:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
				break;
			case ORIENTATION_LANDSCAPE:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
				break;
			case ORIENTATION_DEFAULT:
			default:
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
				break;
		}
	}

	private void loadMIDlet() throws Exception {
		LinkedHashMap<String, String> midlets = microLoader.loadMIDletList();
		int size = midlets.size();
		String[] midletsNameArray = midlets.values().toArray(new String[0]);
		String[] midletsClassArray = midlets.keySet().toArray(new String[0]);
		if (size == 0) {
			throw new Exception("No MIDlets found");
		} else if (size == 1) {
			startAndAttachDebug(midletsClassArray[0]);
		} else {
			showMidletDialog(midletsNameArray, midletsClassArray);
		}
	}

	private void startAndAttachDebug(String className) {
		MidletThread.create(microLoader, className);

		byte[] gameMemoryBuffer = getGameMemoryBuffer();
		JLMemoryDebugService debugService = JLMemoryDebugService.getInstance();
		if (debugService != null && gameMemoryBuffer != null) {
			debugService.setVMMemory(gameMemoryBuffer);
		}
	}

	private byte[] getGameMemoryBuffer() {
		try {
			java.lang.reflect.Field field = MidletThread.class.getDeclaredField("ram");
			field.setAccessible(true);
			Object ramObj = field.get(null);
			if (ramObj instanceof byte[]) {
				return (byte[]) ramObj;
			}
		} catch (Throwable ignored) {
			return null;
		}
		return null;
	}

	private void showMidletDialog(String[] names, final String[] classes) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setTitle(R.string.select_dialog_title)
				.setItems(names, (d, n) -> {
					String clazz = classes[n];
					ErrorReporter errorReporter = ACRA.getErrorReporter();
					String report = errorReporter.getCustomData(Constants.KEY_APPCENTER_ATTACHMENT);
					StringBuilder sb = new StringBuilder();
					if (report != null) {
						sb.append(report).append("\n");
					}
					sb.append("Begin app: ").append(names[n]).append(", ").append(clazz);
					errorReporter.putCustomData(Constants.KEY_APPCENTER_ATTACHMENT, sb.toString());
					startAndAttachDebug(clazz);
					MidletThread.resumeApp();
				})
				.setOnCancelListener(d -> {
					d.dismiss();
					MidletThread.notifyDestroyed();
				});
		builder.show();
	}

	void showErrorDialog(String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setIcon(android.R.drawable.ic_dialog_alert)
				.setTitle(R.string.error)
				.setMessage(message)
				.setPositiveButton(android.R.string.ok, (d, w) -> MidletThread.notifyDestroyed());
		builder.setOnCancelListener(dialogInterface -> MidletThread.notifyDestroyed());
		builder.show();
	}

	private int getToolBarHeight() {
		int[] attrs = new int[]{androidx.appcompat.R.attr.actionBarSize};
		TypedArray ta = obtainStyledAttributes(attrs);
		int toolBarHeight = ta.getDimensionPixelSize(0, -1);
		ta.recycle();
		return toolBarHeight;
	}

	private void hideSystemUI() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			int flags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
			if (!statusBarEnabled) {
				flags |= View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
						| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN;
			}
			getWindow().getDecorView().setSystemUiVisibility(flags);
		} else if (!statusBarEnabled) {
			getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
					WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}
	}

	private void showSystemUI() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
			getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
		} else {
			getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}
	}

	public void setCurrent(Displayable displayable) {
		ViewHandler.postEvent(new SetCurrentEvent(current, displayable));
		current = displayable;
		
		if (displayable instanceof Canvas) {
			Graphics g = Graphics.getCurrentInstance();
			if (g != null && appPath != null) {
				File translationFile = new File(appPath, "translation.json");
				if (translationFile.exists()) {
					g.loadTranslationsFromFile(translationFile);
				}
			}
		}
	}

	public Displayable getCurrent() {
		return current;
	}

	public boolean isVisible() {
		return visible;
	}

	public void showExitConfirmation() {
		AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
		alertBuilder.setTitle(R.string.CONFIRMATION_REQUIRED)
				.setMessage(R.string.FORCE_CLOSE_CONFIRMATION)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					hideSoftInput();
					saveDumpAutomatically();
					MidletThread.destroyApp();
				})
				.setNeutralButton(R.string.action_settings, (d, w) -> {
					hideSoftInput();
					saveDumpAutomatically();
					Config.startApp(this, appName, appPath, true);
					MidletThread.destroyApp();
				})
				.setNegativeButton(android.R.string.cancel, null);
		alertBuilder.create().show();
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		// Mencegat input tombol fisik/virtual untuk mendeteksi kombinasi #0#
		if (event.getAction() == KeyEvent.ACTION_DOWN) {
			char key = (char) event.getUnicodeChar();
			if (key == '#' || key == '0') {
				keySequence.append(key);
				if (keySequence.length() > 3) {
					keySequence.deleteCharAt(0);
				}

				if (keySequence.toString().equals("#0#")) {
					keySequence.setLength(0);
					showMemoryHackDialog();
					return true; // Cegah event diteruskan ke game
				}
			} else {
				keySequence.setLength(0);
			}
		}

		if (event.getKeyCode() == KeyEvent.KEYCODE_MENU)
			if (current instanceof Canvas && binding.displayableContainer.dispatchKeyEvent(event)) {
				return true;
			} else if (event.getAction() == KeyEvent.ACTION_DOWN) {
				if (event.getRepeatCount() == 0) {
					event.startTracking();
					return true;
				} else if (event.isLongPress()) {
					return onKeyLongPress(event.getKeyCode(), event);
				}
			} else if (event.getAction() == KeyEvent.ACTION_UP) {
				return onKeyUp(event.getKeyCode(), event);
			}
		return super.dispatchKeyEvent(event);
	}

	@Override
	public void openOptionsMenu() {
		if (!actionBarEnabled &&
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && current instanceof Canvas) {
			showSystemUI();
		}
		super.openOptionsMenu();
	}

	@Override
	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		if (keyCode == menuKey || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
			showExitConfirmation();
			return true;
		}
		return super.onKeyLongPress(keyCode, event);
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_MENU) {
			return false;
		}
		return super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if ((keyCode == menuKey || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU)
				&& (event.getFlags() & (KeyEvent.FLAG_LONG_PRESS | KeyEvent.FLAG_CANCELED)) == 0) {
			openOptionsMenu();
			return true;
		}
		return super.onKeyUp(keyCode, event);
	}

	@Override
	public void onBackPressed() {
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.midlet_displayable, menu);

		// Menambahkan menu "Memory Injector" secara langsung ke Action Bar / Option Menu
		menu.add(0, 9991, 0, "Memory Injector");

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
			menu.findItem(R.id.action_lock_orientation).setVisible(true);
		}
		if (actionBarEnabled) {
			menu.findItem(R.id.action_ime_keyboard).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
			menu.findItem(R.id.action_take_screenshot).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		}
		if (inputMethodManager == null) {
			menu.findItem(R.id.action_ime_keyboard).setVisible(false);
		}
		if (ContextHolder.getVk() == null) {
			menu.findItem(R.id.action_submenu_vk).setVisible(false);
		}
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (current instanceof Canvas) {
			menu.setGroupVisible(R.id.action_group_canvas, true);
			VirtualKeyboard vk = ContextHolder.getVk();
			if (vk != null) {
				boolean visible = vk.getLayoutEditMode() != VirtualKeyboard.LAYOUT_EOF;
				menu.findItem(R.id.action_layout_edit_finish).setVisible(visible);
			}
		} else {
			menu.setGroupVisible(R.id.action_group_canvas, false);
		}
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		int id = item.getItemId();
		if (id == 9991) {
			showMemoryHackDialog();
			return true;
		} else if (id == R.id.action_exit_midlet) {
			showExitConfirmation();
		} else if (id == R.id.action_save_log) {
			saveLog();
		} else if (id == R.id.action_lock_orientation) {
			if (item.isChecked()) {
				VirtualKeyboard vk = ContextHolder.getVk();
				int orientation = vk != null && vk.isPhone() ? ORIENTATION_PORTRAIT : microLoader.getOrientation();
				setOrientation(orientation);
				item.setChecked(false);
			} else {
				item.setChecked(true);
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
			}
		} else if (id == R.id.action_ime_keyboard) {
			inputMethodManager.toggleSoftInputFromWindow(binding.displayableContainer.getWindowToken(),
					InputMethodManager.SHOW_FORCED, 0);
		} else if (id == R.id.action_take_screenshot) {
			takeScreenshot();
		} else if (id == R.id.action_limit_fps) {
			showLimitFpsDialog();
		} else if (ContextHolder.getVk() != null) {
			handleVkOptions(id);
		}
		return true;
	}

	/**
	 * Pop-Up Dialog Live Injector & Reader langsung ke RAM MidletThread
	 */
	private void showMemoryHackDialog() {
		/*
		 * Real scanner untuk RAM virtual MIDletThread:
		 * First Scan -> menyimpan semua alamat yang cocok.
		 * Next Scan  -> membaca RAM LIVE lagi dan menyaring hasil lama.
		 * Inject     -> menulis langsung ke buffer RAM yang dipakai MIDlet.
		 */
		final byte[] ram = getGameMemoryBuffer();
		if (ram == null || ram.length < 4) {
			Toast.makeText(this, "RAM Game tidak tersedia!", Toast.LENGTH_LONG).show();
			return;
		}

		final java.util.List<Integer> results = new ArrayList<>();
		final LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(28, 18, 28, 8);

		final EditText valueInput = new EditText(this);
		valueInput.setHint("Nilai Integer");
		valueInput.setSingleLine(true);
		valueInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
		root.addView(valueInput, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		final EditText addressInput = new EditText(this);
		addressInput.setHint("Alamat Hex untuk Inject, contoh 0x0012FA");
		addressInput.setSingleLine(true);
		root.addView(addressInput, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		final EditText injectValueInput = new EditText(this);
		injectValueInput.setHint("Nilai baru untuk Inject");
		injectValueInput.setSingleLine(true);
		injectValueInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
		root.addView(injectValueInput, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		final android.widget.TextView status = new android.widget.TextView(this);
		status.setPadding(0, 14, 0, 8);
		status.setText("First Scan belum dilakukan.");
		root.addView(status);

		final android.widget.ListView listView = new android.widget.ListView(this);
		final ArrayList<String> rows = new ArrayList<>();
		final android.widget.ArrayAdapter<String> adapter =
				new android.widget.ArrayAdapter<>(this,
						android.R.layout.simple_list_item_1, rows);
		listView.setAdapter(adapter);

		final android.widget.Button firstButton = new android.widget.Button(this);
		firstButton.setText("FIRST SCAN");
		final android.widget.Button nextButton = new android.widget.Button(this);
		nextButton.setText("NEXT SCAN");
		final android.widget.Button injectButton = new android.widget.Button(this);
		injectButton.setText("INJECT");
		final android.widget.Button injectAllButton = new android.widget.Button(this);
		injectAllButton.setText("INJECT ALL");

		LinearLayout buttons = new LinearLayout(this);
		buttons.setOrientation(LinearLayout.HORIZONTAL);
		buttons.addView(firstButton, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1));
		buttons.addView(nextButton, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1));
		root.addView(buttons);

		LinearLayout injectButtons = new LinearLayout(this);
		injectButtons.setOrientation(LinearLayout.HORIZONTAL);
		injectButtons.addView(injectButton, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1));
		injectButtons.addView(injectAllButton, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1));
		root.addView(injectButtons);

		android.widget.ScrollView resultScroll = new android.widget.ScrollView(this);
		resultScroll.addView(listView, new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, 520));
		root.addView(resultScroll, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

		final AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle("Memory Cheat Engine")
				.setMessage("RAM aktif: " + (ram.length / 1024 / 1024) + " MB")
				.setView(root)
				.setNegativeButton("Tutup", null)
				.create();

		Runnable refreshList = () -> {
			rows.clear();
			int displayCount = Math.min(results.size(), 5000);
			for (int i = 0; i < displayCount; i++) {
				int address = results.get(i);
				int currentValue = MemoryEngine.readInt(MemoryEngine.getLiveRam(), address);
				rows.add(String.format("0x%08X  =  %d", address, currentValue));
			}
			if (results.size() > displayCount) {
				rows.add("... " + (results.size() - displayCount) + " hasil lainnya");
			}
			adapter.notifyDataSetChanged();
			status.setText("Hasil: " + results.size());
		};

		firstButton.setOnClickListener(v -> {
			try {
				int target = Integer.parseInt(valueInput.getText().toString().trim());
				byte[] liveRam = MemoryEngine.getLiveRam();
				results.clear();
				results.addAll(MemoryEngine.firstScanInt(liveRam, target));
				refreshList.run();
				Toast.makeText(this, "First Scan selesai: " + results.size() + " alamat",
						Toast.LENGTH_SHORT).show();
			} catch (NumberFormatException e) {
				Toast.makeText(this, "Masukkan nilai Integer yang valid!",
						Toast.LENGTH_SHORT).show();
			}
		});

		nextButton.setOnClickListener(v -> {
			if (results.isEmpty()) {
				Toast.makeText(this, "Lakukan First Scan terlebih dahulu!",
						Toast.LENGTH_SHORT).show();
				return;
			}
			try {
				int target = Integer.parseInt(valueInput.getText().toString().trim());
				byte[] liveRam = MemoryEngine.getLiveRam();
				java.util.List<Integer> filtered =
						MemoryEngine.nextScanInt(liveRam, results, target);
				results.clear();
				results.addAll(filtered);
				refreshList.run();
				Toast.makeText(this, "Next Scan selesai: " + results.size() + " alamat",
						Toast.LENGTH_SHORT).show();
			} catch (NumberFormatException e) {
				Toast.makeText(this, "Masukkan nilai Integer yang valid!",
						Toast.LENGTH_SHORT).show();
			}
		});

		listView.setOnItemClickListener((parent, view1, position, id) -> {
			if (position >= results.size()) return;
			int address = results.get(position);
			addressInput.setText(String.format("0x%08X", address));
			byte[] liveRam = MemoryEngine.getLiveRam();
			injectValueInput.setText(String.valueOf(MemoryEngine.readInt(liveRam, address)));
		});

		injectButton.setOnClickListener(v -> {
			try {
				String text = addressInput.getText().toString()
						.trim().replace("0x", "").replace("0X", "");
				int address = (int) Long.parseLong(text, 16);
				int newValue = Integer.parseInt(injectValueInput.getText().toString().trim());

				byte[] liveRam = MemoryEngine.getLiveRam();
				if (MemoryEngine.writeInt(liveRam, address, newValue)) {
					Toast.makeText(this,
							String.format("Injected 0x%08X = %d", address, newValue),
							Toast.LENGTH_SHORT).show();
					refreshList.run();
				} else {
					Toast.makeText(this, "Alamat di luar RAM!", Toast.LENGTH_SHORT).show();
				}
			} catch (NumberFormatException e) {
				Toast.makeText(this, "Alamat atau nilai Inject tidak valid!",
						Toast.LENGTH_SHORT).show();
			}
		});

		injectAllButton.setOnClickListener(v -> {
			if (results.isEmpty()) {
				Toast.makeText(this, "Tidak ada hasil untuk di-inject!",
						Toast.LENGTH_SHORT).show();
				return;
			}
			try {
				int newValue = Integer.parseInt(injectValueInput.getText().toString().trim());
				byte[] liveRam = MemoryEngine.getLiveRam();
				int count = 0;
				for (Integer address : results) {
					if (MemoryEngine.writeInt(liveRam, address, newValue)) count++;
				}
				refreshList.run();
				Toast.makeText(this, "Inject " + count + " alamat selesai.",
						Toast.LENGTH_SHORT).show();
			} catch (NumberFormatException e) {
				Toast.makeText(this, "Masukkan nilai baru yang valid!",
						Toast.LENGTH_SHORT).show();
			}
		});

		dialog.show();
	}

	private void handleVkOptions(int id) {
		VirtualKeyboard vk = ContextHolder.getVk();
		if (id == R.id.action_layout_edit_mode) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_KEYS);
			Toast.makeText(this, R.string.layout_edit_mode, Toast.LENGTH_SHORT).show();
		} else if (id == R.id.action_layout_scale_mode) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_SCALES);
			Toast.makeText(this, R.string.layout_scale_mode, Toast.LENGTH_SHORT).show();
		} else if (id == R.id.action_layout_edit_finish) {
			vk.setLayoutEditMode(VirtualKeyboard.LAYOUT_EOF);
			Toast.makeText(this, R.string.layout_edit_finished, Toast.LENGTH_SHORT).show();
			showSaveVkAlert(false);
		} else if (id == R.id.action_layout_switch) {
			showSetLayoutDialog();
		} else if (id == R.id.action_hide_buttons) {
			showHideButtonDialog();
		}
	}

	@SuppressLint("CheckResult")
	private void takeScreenshot() {
		microLoader.takeScreenshot((Canvas) current, new SingleObserver<String>() {
			@Override
			public void onSubscribe(@NonNull Disposable d) {
			}

			@Override
			public void onSuccess(@NonNull String s) {
				Toast.makeText(MicroActivity.this, getString(R.string.screenshot_saved)
						+ " " + s, Toast.LENGTH_LONG).show();
			}

			@Override
			public void onError(@NonNull Throwable e) {
				e.printStackTrace();
				Toast.makeText(MicroActivity.this, R.string.error, Toast.LENGTH_SHORT).show();
			}
		});
	}

	private void saveLog() {
		try {
			LogUtils.writeLog();
			Toast.makeText(this, R.string.log_saved, Toast.LENGTH_SHORT).show();
		} catch (IOException e) {
			e.printStackTrace();
			Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
		}
	}

	private void showHideButtonDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		boolean[] states = vk.getKeysVisibility();
		boolean[] changed = states.clone();
		new AlertDialog.Builder(this)
				.setTitle(R.string.hide_buttons)
				.setMultiChoiceItems(vk.getKeyNames(), changed, (dialog, which, isChecked) -> {})
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					if (!Arrays.equals(states, changed)) {
						vk.setKeysVisibility(changed);
						showSaveVkAlert(true);
					}
				}).show();
	}

	private void showSaveVkAlert(boolean keepScreenPreferred) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.CONFIRMATION_REQUIRED);
		builder.setMessage(R.string.pref_vk_save_alert);
		builder.setNegativeButton(android.R.string.no, null);
		AlertDialog dialog = builder.create();

		final VirtualKeyboard vk = ContextHolder.getVk();
		if (vk.isPhone()) {
			AppCompatCheckBox cb = new AppCompatCheckBox(this);
			cb.setText(R.string.opt_save_screen_params);
			cb.setChecked(keepScreenPreferred);

			TypedValue out = new TypedValue();
			getTheme().resolveAttribute(androidx.appcompat.R.attr.dialogPreferredPadding, out, true);
			int paddingH = getResources().getDimensionPixelOffset(out.resourceId);
			int paddingT = getResources().getDimensionPixelOffset(androidx.appcompat.R.dimen.abc_dialog_padding_top_material);
			dialog.setView(cb, paddingH, paddingT, paddingH, 0);

			dialog.setButton(dialog.BUTTON_POSITIVE, getText(android.R.string.yes), (d, w) -> {
				if (cb.isChecked()) {
					vk.saveScreenParams();
				}
				vk.onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM);
			});
		} else {
			dialog.setButton(dialog.BUTTON_POSITIVE, getText(android.R.string.yes), (d, w) ->
					ContextHolder.getVk().onLayoutChanged(VirtualKeyboard.TYPE_CUSTOM));
		}
		dialog.show();
	}

	private void showSetLayoutDialog() {
		final VirtualKeyboard vk = ContextHolder.getVk();
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setTitle(R.string.layout_switch)
				.setSingleChoiceItems(R.array.PREF_VK_TYPE_ENTRIES, vk.getLayout(), null)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					vk.setLayout(((AlertDialog) d).getListView().getCheckedItemPosition());
					if (vk.isPhone()) {
						setOrientation(ORIENTATION_PORTRAIT);
					} else {
						setOrientation(microLoader.getOrientation());
					}
				});
		builder.show();
	}

	private void showLimitFpsDialog() {
		EditText editText = new EditText(this);
		editText.setHint(R.string.unlimited);
		editText.setInputType(InputType.TYPE_CLASS_NUMBER);
		editText.setKeyListener(DigitsKeyListener.getInstance("0123456789"));
		editText.setMaxLines(1);
		editText.setSingleLine(true);
		float density = getResources().getDisplayMetrics().density;
		LinearLayout linearLayout = new LinearLayout(this);
		LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT);
		int margin = (int) (density * 20);
		params.setMargins(margin, 0, margin, 0);
		linearLayout.addView(editText, params);
		int paddingVertical = (int) (density * 16);
		int paddingHorizontal = (int) (density * 8);
		editText.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);
		new AlertDialog.Builder(this)
				.setTitle(R.string.PREF_LIMIT_FPS)
				.setView(linearLayout)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					Editable text = editText.getText();
					int fps = 0;
					try {
						fps = TextUtils.isEmpty(text) ? 0 : Integer.parseInt(text.toString().trim());
					} catch (NumberFormatException ignored) {
					}
					microLoader.setLimitFps(fps);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.setNeutralButton(R.string.reset, ((d, which) -> microLoader.setLimitFps(-1)))
				.show();
	}

	@Override
	public boolean onContextItemSelected(@NonNull MenuItem item) {
		if (current instanceof Form) {
			((Form) current).contextMenuItemSelected(item);
		} else if (current instanceof List) {
			AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
			((List) current).contextMenuItemSelected(item, info.position);
		}

		return super.onContextItemSelected(item);
	}

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		ContextHolder.notifyOnActivityResult(requestCode, resultCode, data);
	}

	public String getAppName() {
		return appName;
	}

	private void saveDumpAutomatically() {
		try {
			Graphics g = Graphics.getCurrentInstance();
			if (g != null && appPath != null) {
				File dumpFile = new File(appPath, "dump.json");
				g.saveDumpToJSON(dumpFile);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private class SetCurrentEvent extends SimpleEvent {
		private final Displayable current;
		private final Displayable next;

		private SetCurrentEvent(Displayable current, Displayable next) {
			this.current = current;
			this.next = next;
		}

		@Override
		public void process() {
			closeOptionsMenu();
			if (current != null) {
				current.clearDisplayableView();
			}
			if (next instanceof Alert) {
				return;
			}
			binding.displayableContainer.removeAllViews();
			ActionBar actionBar = Objects.requireNonNull(getSupportActionBar());
			LinearLayout.LayoutParams layoutParams = (LinearLayout.LayoutParams) binding.toolbar.getLayoutParams();
			int toolbarHeight = 0;
			if (next instanceof Canvas) {
				hideSystemUI();
				if (!actionBarEnabled) {
					actionBar.hide();
				} else {
					final String title = next.getTitle();
					actionBar.setTitle(title == null ? appName : title);
					toolbarHeight = (int) (getToolBarHeight() / 1.5);
					layoutParams.height = toolbarHeight;
				}
			} else {
				showSystemUI();
				actionBar.show();
				final String title = next != null ? next.getTitle() : null;
				actionBar.setTitle(title == null ? appName : title);
				toolbarHeight = getToolBarHeight();
				layoutParams.height = toolbarHeight;
			}
			binding.overlayView.setLocation(0, toolbarHeight);
			binding.toolbar.setLayoutParams(layoutParams);
			invalidateOptionsMenu();
			if (next != null) {
				binding.displayableContainer.addView(next.getDisplayableView());
			}
		}
	}

	@Override
	protected void onDestroy() {
		saveDumpAutomatically();
		
		binding = null;
		super.onDestroy();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == 1) {
			synchronized (LocationProviderImpl.permissionLock) {
				LocationProviderImpl.permissionLock.notify();
			}
			LocationProviderImpl.permissionResult = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
		}
	}
}
