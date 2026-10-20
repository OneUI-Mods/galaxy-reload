package dev.ryuya.galaxyreload;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.*;
import androidx.core.widget.NestedScrollView;
import androidx.lifecycle.*;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.textview.MaterialTextView;
import java.util.concurrent.*;

/** Initial distribution: acquires this application's SU grant without restarting anything. */
public final class MainActivity extends AppCompatActivity {
  public static final class RootState extends ViewModel {
    final MutableLiveData<Integer> result = new MutableLiveData<>(R.string.reload_root_unknown);
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    volatile boolean busy;

    void check() {
      if (busy) return;
      busy = true;
      result.setValue(R.string.reload_root_waiting);
      worker.execute(
          () -> {
            int message;
            try {
              RootShell.requireRoot();
              message = R.string.reload_root_allowed;
            } catch (RootShell.TimedOut timeout) {
              message = R.string.reload_root_timeout;
            } catch (Exception denied) {
              message = R.string.reload_root_denied;
            }
            busy = false;
            result.postValue(message);
          });
    }

    @Override
    protected void onCleared() {
      worker.shutdownNow();
    }
  }

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
    NestedScrollView scroll = new NestedScrollView(this);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(20), dp(20), dp(20), dp(24));
    scroll.addView(body);
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.ic_guard);
    icon.setContentDescription(null);
    icon.setImageTintList(
        android.content.res.ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(
                icon, com.google.android.material.R.attr.colorPrimary)));
    body.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
    text(
        body,
        R.string.reload_heading,
        com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
    text(
        body,
        R.string.reload_intro,
        com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
    MaterialCardView card = new MaterialCardView(this);
    LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
    cardParams.setMargins(0, dp(24), 0, dp(20));
    body.addView(card, cardParams);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(20), dp(20), dp(20));
    card.addView(content);
    text(
        content,
        R.string.reload_root_title,
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    MaterialTextView status =
        text(
            content,
            R.string.reload_root_unknown,
            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
    MaterialButton check = new MaterialButton(this);
    check.setText(R.string.reload_root_check);
    content.addView(check, new LinearLayout.LayoutParams(-1, -2));
    RootState model = new ViewModelProvider(this).get(RootState.class);
    model.result.observe(
        this,
        value -> {
          status.setText(value);
          check.setEnabled(value != R.string.reload_root_waiting);
        });
    check.setOnClickListener(v -> model.check());
    text(
        body,
        R.string.reload_preparation,
        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
    text(
        body,
        R.string.reload_preparation_note,
        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
    MaterialButton reload = new MaterialButton(this);
    reload.setText(R.string.reload_action);
    reload.setEnabled(false);
    body.addView(reload, new LinearLayout.LayoutParams(-1, -2));
    MaterialButton legal =
        new MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle);
    legal.setText(R.string.legal_title);
    legal.setOnClickListener(v -> startActivity(new Intent(this, LicensesActivity.class)));
    body.addView(legal);
    MaterialTextView version =
        text(
            body,
            R.string.reload_version,
            com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
    version.setText(getString(R.string.reload_version, BuildConfig.VERSION_NAME));
  }

  private MaterialTextView text(LinearLayout parent, int label, int style) {
    MaterialTextView text = new MaterialTextView(this);
    text.setText(label);
    text.setTextAppearance(style);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, dp(8), 0, dp(8));
    parent.addView(text, lp);
    return text;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
