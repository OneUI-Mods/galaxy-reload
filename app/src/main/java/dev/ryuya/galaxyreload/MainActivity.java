package dev.ryuya.galaxyreload;

import android.content.*;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.*;
import androidx.lifecycle.*;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.textview.MaterialTextView;
import java.util.concurrent.*;

public final class MainActivity extends AppCompatActivity {
  public record State(boolean busy, boolean root, String result) {}

  public static final class Operation extends ViewModel {
    final MutableLiveData<State> state = new MutableLiveData<>(new State(false, false, "UNKNOWN"));
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    void run(String apk, String action) {
      State previous = state.getValue();
      if (previous != null && previous.busy()) return;
      boolean allowed = previous != null && previous.root();
      state.setValue(new State(true, allowed, "WAITING"));
      worker.execute(
          () -> {
            boolean root = allowed;
            String result;
            try {
              RootShell.requireRoot();
              root = true;
              String command =
                  "CLASSPATH="
                      + quote(apk)
                      + " /system/bin/app_process / dev.ryuya.galaxyreload.ReloadCommand "
                      + action;
              var outcome = RootShell.execute(command);
              String[] lines = outcome.text().trim().split("\\R");
              result = lines.length == 0 ? "FAILED" : lines[lines.length - 1];
              if (outcome.code() != 0)
                android.util.Log.w(
                    "GalaxyReload", "Helper exit " + outcome.code() + ": " + outcome.text());
            } catch (RootShell.Denied denied) {
              root = false;
              result = "ROOT_REQUIRED";
            } catch (RootShell.TimedOut timeout) {
              result = "TIMEOUT";
            } catch (Exception failure) {
              android.util.Log.e("GalaxyReload", "Unable to invoke helper", failure);
              result = "FAILED";
            }
            state.postValue(new State(false, root, result));
          });
    }

    private static String quote(String value) {
      return "'" + value.replace("'", "'\\''") + "'";
    }

    @Override
    protected void onCleared() {
      worker.shutdownNow();
    }
  }

  private SettingsTabs pages;
  private static final String CLI_COMMAND =
      "adb shell su -c '/data/local/tmp/galaxy-reload reload'";

  @Override
  public void onCreate(Bundle saved) {
    DynamicColors.applyToActivityIfAvailable(this);
    super.onCreate(saved);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    setContentView(root);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (view, insets) -> {
          var bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    MaterialToolbar toolbar = new MaterialToolbar(this);
    toolbar.setTitle(R.string.app_name);
    root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));
    pages = new SettingsTabs(this, root, saved);
    LinearLayout reloadPage = pages.page(R.string.reload_tab);
    LinearLayout commandPage = pages.page(R.string.reload_cli_tab);
    LinearLayout infoPage = pages.page(R.string.reload_info_tab);
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.ic_guard);
    icon.setContentDescription(null);
    icon.setImageTintList(
        android.content.res.ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(
                icon, androidx.appcompat.R.attr.colorPrimary)));
    reloadPage.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
    text(
        reloadPage,
        R.string.reload_heading,
        com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
    text(
        reloadPage,
        R.string.reload_intro,
        com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
    LinearLayout access = card(reloadPage);
    text(
        access,
        R.string.reload_root_title,
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    MaterialTextView permission =
        text(
            access,
            R.string.reload_root_unknown,
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    MaterialButton check = button(access, R.string.reload_root_check);
    LinearLayout operation = card(reloadPage);
    MaterialTextView status =
        text(
            operation,
            R.string.reload_status_unknown,
            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
    MaterialButton reload = button(operation, R.string.reload_action);
    reload.setIconResource(R.drawable.ic_guard);
    text(
        operation,
        R.string.reload_scope,
        com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
    text(
        commandPage,
        R.string.reload_cli_heading,
        com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
    text(
        commandPage,
        R.string.reload_cli_note,
        com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
    LinearLayout cli = card(commandPage);
    MaterialButton install = button(cli, R.string.reload_cli_install);
    MaterialTextView commandStatus =
        text(
            cli,
            R.string.reload_status_unknown,
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    MaterialTextView command =
        text(
            cli,
            R.string.reload_cli_command,
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    command.setText(CLI_COMMAND);
    command.setTypeface(android.graphics.Typeface.MONOSPACE);
    command.setTextIsSelectable(true);
    button(cli, R.string.reload_cli_copy)
        .setOnClickListener(
            v -> {
              getSystemService(ClipboardManager.class)
                  .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), CLI_COMMAND));
            });
    text(
        cli,
        R.string.reload_cli_check_note,
        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    text(
        infoPage,
        R.string.reload_requirements_title,
        com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
    text(
        infoPage,
        R.string.reload_requirements,
        com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
    button(infoPage, R.string.legal_title)
        .setOnClickListener(v -> startActivity(new Intent(this, LicensesActivity.class)));
    MaterialTextView version =
        text(
            infoPage,
            R.string.reload_version,
            com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
    version.setText(getString(R.string.reload_version, BuildConfig.VERSION_NAME));
    Operation model = new ViewModelProvider(this).get(Operation.class);
    String apk = getApplicationInfo().sourceDir;
    check.setOnClickListener(v -> model.run(apk, "check"));
    reload.setOnClickListener(v -> model.run(apk, "reload"));
    install.setOnClickListener(v -> model.run(apk, "install-cli"));
    model.state.observe(
        this,
        value -> {
          permission.setText(
              value.root() ? R.string.reload_root_allowed : R.string.reload_root_unknown);
          status.setText(message(value.result()));
          commandStatus.setText(message(value.result()));
          check.setEnabled(!value.busy());
          reload.setEnabled(!value.busy());
          install.setEnabled(!value.busy());
        });
    pages.restore();
  }

  private int message(String code) {
    return switch (code) {
      case "READY" -> R.string.reload_status_ready;
      case "SETUP" -> R.string.reload_status_setup;
      case "RELOADED" -> R.string.reload_status_done;
      case "INSTALLED" -> R.string.reload_status_cli;
      case "WAITING" -> R.string.reload_root_waiting;
      case "ROOT_REQUIRED" -> R.string.reload_root_denied;
      case "TIMEOUT" -> R.string.reload_root_timeout;
      case "LOCKED", "SCREEN_OFF" -> R.string.reload_status_locked;
      case "MODULE_REQUIRED" -> R.string.reload_status_module;
      case "UNSUPPORTED", "UNSUPPORTED_USER" -> R.string.reload_status_unsupported;
      case "UNLOCK_NEEDED" -> R.string.reload_status_unlock;
      case "CLI_CONFLICT" -> R.string.reload_status_cli_conflict;
      case "BUSY" -> R.string.reload_status_busy;
      case "UNKNOWN" -> R.string.reload_status_unknown;
      default -> R.string.reload_status_failed;
    };
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    pages.save(out);
    super.onSaveInstanceState(out);
  }

  private LinearLayout card(LinearLayout parent) {
    MaterialCardView card = new MaterialCardView(this);
    card.setRadius(dp(24));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, dp(16), 0, dp(8));
    parent.addView(card, lp);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(20), dp(20), dp(20), dp(20));
    card.addView(body);
    return body;
  }

  private MaterialButton button(LinearLayout parent, int label) {
    MaterialButton button = new MaterialButton(this);
    button.setText(label);
    parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
    return button;
  }

  private MaterialTextView text(LinearLayout parent, int label, int style) {
    MaterialTextView view = new MaterialTextView(this);
    view.setText(label);
    view.setTextAppearance(style);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, dp(8), 0, dp(8));
    parent.addView(view, lp);
    return view;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
