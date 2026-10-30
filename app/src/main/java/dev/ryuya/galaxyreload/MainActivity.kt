package dev.ryuya.galaxyreload

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.View.OnClickListener
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import java.util.concurrent.Executors
import java.util.regex.Pattern
import kotlin.jvm.JvmRecord

class MainActivity : AppCompatActivity() {
    @JvmRecord
    data class State(
        val busy: Boolean,
        val root: Boolean,
        val result: String,
    ) {
        override fun toString(): String = "State[busy=$busy, root=$root, result=$result]"
    }

    class Operation : ViewModel() {
        val state = MutableLiveData<MainActivity.State>(MainActivity.State(false, false, "UNKNOWN"))
        private val worker = Executors.newSingleThreadExecutor()

        fun run(apk: String, action: String?) {
            val previous = state.getValue()
            if (previous != null && previous.busy) return
            val allowed = previous != null && previous.root
            state.setValue(MainActivity.State(true, allowed, "WAITING"))
            worker.execute(
                Runnable {
                    var root = allowed
                    var result: String?
                    try {
                        RootShell.requireRoot()
                        root = true
                        val command =
                            ("CLASSPATH=" +
                                MainActivity.Operation.quote(apk) +
                                " /system/bin/app_process / dev.ryuya.galaxyreload.ReloadCommand " +
                                action)
                        val outcome = RootShell.execute(command)
                        val lines = Pattern.compile("\\R").split(outcome.text.trim())
                        result = if (lines.size == 0) "FAILED" else lines[lines.size - 1]
                        if (outcome.code !== 0)
                            Log.w(
                                "GalaxyReload",
                                "Helper exit " + outcome.code + ": " + outcome.text,
                            )
                    } catch (denied: RootShell.Denied) {
                        root = false
                        result = "ROOT_REQUIRED"
                    } catch (timeout: RootShell.TimedOut) {
                        result = "TIMEOUT"
                    } catch (failure: Exception) {
                        Log.e("GalaxyReload", "Unable to invoke helper", failure)
                        result = "FAILED"
                    }
                    state.postValue(MainActivity.State(false, root, result))
                }
            )
        }

        override fun onCleared() {
            worker.shutdownNow()
        }

        companion object {
            private fun quote(value: String): String {
                return "'" + value.replace("'", "'\\''") + "'"
            }
        }
    }

    private lateinit var pages: SettingsTabs

    public override fun onCreate(saved: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(saved)
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false)
        val root = LinearLayout(this)
        root.setOrientation(LinearLayout.VERTICAL)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(
            root,
            OnApplyWindowInsetsListener { view: View, insets: WindowInsetsCompat ->
                val bars = insets.getInsets(Type.systemBars() or Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            },
        )
        val toolbar = MaterialToolbar(this)
        toolbar.setTitle(R.string.app_name)
        root.addView(toolbar, LayoutParams(-1, dp(64)))
        pages = SettingsTabs(this, root, saved)
        val reloadPage = pages.page(R.string.reload_tab)
        val commandPage = pages.page(R.string.reload_cli_tab)
        val infoPage = pages.page(R.string.reload_info_tab)
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_guard)
        icon.setContentDescription(null)
        icon.setImageTintList(
            ColorStateList.valueOf(
                MaterialColors.getColor(
                    icon,
                    androidx.appcompat.R.attr.colorPrimary,
                )
            )
        )
        reloadPage.addView(icon, LayoutParams(dp(72), dp(72)))
        text(
            reloadPage,
            R.string.reload_heading,
            com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall,
        )
        text(
            reloadPage,
            R.string.reload_intro,
            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
        )
        val access = card(reloadPage)
        text(
            access,
            R.string.reload_root_title,
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
        )
        val permission =
            text(
                access,
                R.string.reload_root_unknown,
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
            )
        val check = button(access, R.string.reload_root_check)
        val operation = card(reloadPage)
        val status =
            text(
                operation,
                R.string.reload_status_unknown,
                com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
            )
        val reload = button(operation, R.string.reload_action)
        reload.setIconResource(R.drawable.ic_guard)
        text(
            operation,
            R.string.reload_scope,
            com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
        )
        text(
            commandPage,
            R.string.reload_cli_heading,
            com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall,
        )
        text(
            commandPage,
            R.string.reload_cli_note,
            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
        )
        val cli = card(commandPage)
        val install = button(cli, R.string.reload_cli_install)
        val commandStatus =
            text(
                cli,
                R.string.reload_status_unknown,
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
            )
        val command =
            text(
                cli,
                R.string.reload_cli_command,
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
            )
        command.setText(MainActivity.CLI_COMMAND)
        command.setTypeface(Typeface.MONOSPACE)
        command.setTextIsSelectable(true)
        button(cli, R.string.reload_cli_copy)
            .setOnClickListener(
                OnClickListener { v: View? ->
                    getSystemService<ClipboardManager?>(ClipboardManager::class.java)
                        .setPrimaryClip(
                            ClipData.newPlainText(
                                getString(R.string.app_name),
                                MainActivity.CLI_COMMAND,
                            )
                        )
                }
            )
        text(
            cli,
            R.string.reload_cli_check_note,
            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
        )
        text(
            infoPage,
            R.string.reload_requirements_title,
            com.google.android.material.R.style.TextAppearance_Material3_TitleLarge,
        )
        text(
            infoPage,
            R.string.reload_requirements,
            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
        )
        button(infoPage, R.string.legal_title)
            .setOnClickListener(
                OnClickListener { v: View? ->
                    startActivity(Intent(this, LicensesActivity::class.java))
                }
            )
        val version =
            text(
                infoPage,
                R.string.reload_version,
                com.google.android.material.R.style.TextAppearance_Material3_LabelMedium,
            )
        version.setText(getString(R.string.reload_version, BuildConfig.VERSION_NAME))
        val model =
            ViewModelProvider(this).get<MainActivity.Operation>(MainActivity.Operation::class.java)
        val apk = getApplicationInfo().sourceDir
        check.setOnClickListener(OnClickListener { v: View? -> model.run(apk, "check") })
        reload.setOnClickListener(OnClickListener { v: View? -> model.run(apk, "reload") })
        install.setOnClickListener(
            OnClickListener { v: View? ->
                model.run(apk, "install-cli")
            }
        )
        model.state.observe(
            this,
            Observer { value: MainActivity.State ->
                permission.setText(
                    if (value.root) R.string.reload_root_allowed else R.string.reload_root_unknown
                )
                status.setText(message(value.result))
                commandStatus.setText(message(value.result))
                check.setEnabled(!value.busy)
                reload.setEnabled(!value.busy)
                install.setEnabled(!value.busy)
            },
        )
        pages.restore()
    }

    private fun message(code: String?): Int {
        return when (code) {
            "READY" -> R.string.reload_status_ready
            "SETUP" -> R.string.reload_status_setup
            "RELOADED" -> R.string.reload_status_done
            "INSTALLED" -> R.string.reload_status_cli
            "WAITING" -> R.string.reload_root_waiting
            "ROOT_REQUIRED" -> R.string.reload_root_denied
            "TIMEOUT" -> R.string.reload_root_timeout
            "LOCKED",
            "SCREEN_OFF" -> R.string.reload_status_locked
            "MODULE_REQUIRED" -> R.string.reload_status_module
            "UNSUPPORTED",
            "UNSUPPORTED_USER" -> R.string.reload_status_unsupported
            "UNLOCK_NEEDED" -> R.string.reload_status_unlock
            "CLI_CONFLICT" -> R.string.reload_status_cli_conflict
            "BUSY" -> R.string.reload_status_busy
            "UNKNOWN" -> R.string.reload_status_unknown
            else -> R.string.reload_status_failed
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        pages.save(out)
        super.onSaveInstanceState(out)
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val card = MaterialCardView(this)
        card.setRadius(dp(24).toFloat())
        val lp = LayoutParams(-1, -2)
        lp.setMargins(0, dp(16), 0, dp(8))
        parent.addView(card, lp)
        val body = LinearLayout(this)
        body.setOrientation(LinearLayout.VERTICAL)
        body.setPadding(dp(20), dp(20), dp(20), dp(20))
        card.addView(body)
        return body
    }

    private fun button(
        parent: LinearLayout,
        label: Int,
    ): MaterialButton {
        val button = MaterialButton(this)
        button.setText(label)
        parent.addView(button, LayoutParams(-1, -2))
        return button
    }

    private fun text(
        parent: LinearLayout,
        label: Int,
        style: Int,
    ): MaterialTextView {
        val view = MaterialTextView(this)
        view.setText(label)
        view.setTextAppearance(style)
        val lp = LayoutParams(-1, -2)
        lp.setMargins(0, dp(8), 0, dp(8))
        parent.addView(view, lp)
        return view
    }

    private fun dp(value: Int): Int {
        return Math.round(value * getResources().getDisplayMetrics().density)
    }

    companion object {
        private const val CLI_COMMAND = "adb shell su -c '/data/local/tmp/galaxy-reload reload'"
    }
}
