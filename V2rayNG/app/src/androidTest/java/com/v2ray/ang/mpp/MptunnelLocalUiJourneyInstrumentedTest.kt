package com.v2ray.ang.mpp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.type
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.MppProfileConfig
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Local-only real UI journey; CI intentionally does not run this class.
 *
 * Run from `V2rayNG/` on an interactive emulator:
 * `./gradlew connectedFdroidDebugAndroidTest -PABI_FILTERS=x86_64 -PUNIVERSAL_APK=false -Pandroid.testInstrumentationRunnerArguments.class=com.v2ray.ang.mpp.MptunnelLocalUiJourneyInstrumentedTest`
 */
@RunWith(AndroidJUnit4::class)
class MptunnelLocalUiJourneyInstrumentedTest {

    @Test
    fun defaultProfilePersistsGuidedSelectionsThroughOrdinaryUiJourney() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val remarks = "mpp-ui-${SystemClock.uptimeMillis()}"
        val launcherIntent = requireNotNull(
            context.packageManager.getLaunchIntentForPackage(context.packageName)
        ) { "target package has no launcher activity" }.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )

        try {
            // Start exactly as the launcher does; every profile edit below is a visible UI action.
            context.startActivity(launcherIntent)
            allowNotificationPermissionThroughVisibleUi(
                device,
                context.packageName,
                context.getString(R.string.acc_add),
            )

            clickVisibleLabel(
                device,
                context.getString(R.string.acc_add),
            )
            clickVisibleLabel(
                device,
                context.getString(R.string.menu_item_import_config_manually_mpp),
                scrollForwardWhenMissing = true,
            )

            typeIntoVisibleField(
                device,
                context.getString(R.string.server_lab_remarks),
                remarks,
            )
            assertOrdinarySelectorVisible(
                device = device,
                title = context.getString(R.string.server_mpp_log_level),
                selectedValue = MppProfileConfig.DEFAULT_LOG_LEVEL,
            )
            clickVisibleLabel(
                device,
                context.getString(R.string.server_mpp_log_level),
            )
            clickVisibleLabel(device, "debug")
            assertOrdinarySelectorVisible(
                device = device,
                title = context.getString(R.string.server_mpp_log_level),
                selectedValue = "debug",
            )
            assertOrdinarySelectorVisible(
                device = device,
                title = context.getString(R.string.server_mpp_target_resolution),
                selectedValue = context.getString(R.string.server_mpp_target_resolution_as_is),
            )
            clickVisibleLabel(device, context.getString(R.string.server_mpp_target_resolution))
            clickVisibleLabel(
                device,
                context.getString(R.string.server_mpp_target_resolution_route_only),
            )
            assertOrdinarySelectorVisible(
                device = device,
                title = context.getString(R.string.server_mpp_target_resolution),
                selectedValue = context.getString(R.string.server_mpp_target_resolution_route_only),
            )

            pasteUtf8MaterialThroughVisibleAction(
                device,
                clipboard,
                context.getString(R.string.server_mpp_credential_secret),
                context.getString(R.string.server_mpp_paste_as_text),
                UI_CREDENTIAL_TEXT,
            )
            pasteIntoVisibleField(
                device,
                clipboard,
                context.getString(R.string.server_mpp_pinned_certificate),
                MptunnelNativeInstrumentedTest.TEST_CERTIFICATE,
            )
            pasteUtf8MaterialThroughVisibleAction(
                device,
                clipboard,
                context.getString(R.string.server_mpp_transport_secret),
                context.getString(R.string.server_mpp_paste_as_text),
                UI_TRANSPORT_SECRET_TEXT,
            )

            clickVisibleLabel(device, context.getString(R.string.acc_save))
            waitForVisibleLabel(
                device,
                remarks,
                scrollForwardWhenMissing = true,
            )
            clickProfileEdit(device, remarks, context.getString(R.string.acc_edit))

            assertOrdinarySelectorVisible(
                device = device,
                title = context.getString(R.string.server_mpp_log_level),
                selectedValue = "debug",
            )
            assertOrdinarySelectorVisible(
                device = device,
                title = context.getString(R.string.server_mpp_target_resolution),
                selectedValue = context.getString(R.string.server_mpp_target_resolution_route_only),
            )
        } finally {
            clipboard.clearPrimaryClip()
            repeat(2) {
                device.pressBack()
                SystemClock.sleep(100L)
            }
        }
    }

    private companion object {
        const val UI_RENDER_TIMEOUT_MS = 45_000L
        const val UI_POLL_INTERVAL_MS = 100L
        const val UI_CREDENTIAL_TEXT = "instrumentation-credential-secret"
        const val UI_TRANSPORT_SECRET_TEXT = "0123456789abcdef0123456789abcdef"

        fun assertOrdinarySelectorVisible(
            device: UiDevice,
            title: String,
            selectedValue: String,
        ) {
            val deadline = SystemClock.uptimeMillis() + UI_RENDER_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (findVisibleLabel(device, title) != null &&
                    findVisibleLabel(device, selectedValue) != null
                ) {
                    return
                }
                SystemClock.sleep(UI_POLL_INTERVAL_MS)
            }
            throw AssertionError(
                "ordinary MPP editor did not visibly expose the expected selector row; " +
                        "package=${device.currentPackageName}"
            )
        }

        fun allowNotificationPermissionThroughVisibleUi(
            device: UiDevice,
            targetPackage: String,
            addConfigurationLabel: String,
        ) {
            val deadline = SystemClock.uptimeMillis() + UI_RENDER_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (device.currentPackageName == targetPackage &&
                    findVisibleLabel(device, addConfigurationLabel) != null
                ) {
                    return
                }
                val allow = device.findObject(
                    By.res(Pattern.compile(".*:id/permission_allow_button"))
                ) ?: findVisibleLabel(device, "Allow")
                if (allow != null) {
                    allow.click()
                }
                SystemClock.sleep(UI_POLL_INTERVAL_MS)
            }
            throw AssertionError(
                "launcher UI did not become ready after visible permission handling; " +
                        "package=${device.currentPackageName}"
            )
        }

        fun clickVisibleLabel(
            device: UiDevice,
            label: String,
            scrollForwardWhenMissing: Boolean = false,
        ) {
            waitForVisibleLabel(device, label, scrollForwardWhenMissing).click()
            SystemClock.sleep(UI_POLL_INTERVAL_MS)
        }

        fun waitForVisibleLabel(
            device: UiDevice,
            label: String,
            scrollForwardWhenMissing: Boolean = false,
        ): UiObject2 {
            val deadline = SystemClock.uptimeMillis() + UI_RENDER_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                findVisibleLabel(device, label)?.let { return it }
                if (scrollForwardWhenMissing) scrollForward(device)
                SystemClock.sleep(UI_POLL_INTERVAL_MS)
            }
            throw AssertionError(
                "expected visible UI label was not found; package=${device.currentPackageName}"
            )
        }

        fun typeIntoVisibleField(device: UiDevice, label: String, value: String) {
            waitForVisibleEditableField(device, label).click()
            device.type(value)
            waitForVisibleFieldValue(device, label) { it == value }
        }

        fun pasteIntoVisibleField(
            device: UiDevice,
            clipboard: ClipboardManager,
            label: String,
            value: String,
        ) {
            clipboard.setPrimaryClip(ClipData.newPlainText("MPP test material", value))
            waitForVisibleEditableField(device, label).click()
            SystemClock.sleep(UI_POLL_INTERVAL_MS)
            if (!device.pressKeyCode(KeyEvent.KEYCODE_PASTE)) {
                throw AssertionError("paste key event was not handled by visible field: $label")
            }
            waitForVisibleFieldValue(device, label) { it == value }
        }

        fun pasteUtf8MaterialThroughVisibleAction(
            device: UiDevice,
            clipboard: ClipboardManager,
            fieldLabel: String,
            pasteLabel: String,
            value: String,
        ) {
            clipboard.setPrimaryClip(ClipData.newPlainText("MPP test material", value))
            waitForRelatedVisibleLabel(device, fieldLabel, pasteLabel).click()
            val expectedHex = value.toByteArray(Charsets.UTF_8)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
            waitForVisibleFieldValue(device, fieldLabel) { it.isNotEmpty() }

            val showLabel = InstrumentationRegistry.getInstrumentation().targetContext
                .getString(R.string.acc_show_content)
            waitForRelatedVisibleLabel(device, fieldLabel, showLabel).click()
            waitForVisibleFieldValue(device, fieldLabel) { it == expectedHex }
        }

        fun waitForRelatedVisibleLabel(
            device: UiDevice,
            fieldLabel: String,
            actionLabel: String,
        ): UiObject2 {
            val deadline = SystemClock.uptimeMillis() + UI_RENDER_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                val field = findVisibleEditableField(device, fieldLabel)
                if (field != null) {
                    val fieldCenter = field.visibleCenter
                    findVisibleLabels(device, actionLabel)
                        .minByOrNull { action ->
                            kotlin.math.abs(action.visibleCenter.y - fieldCenter.y)
                        }
                        ?.let { return it }
                }
                scrollForward(device)
                SystemClock.sleep(UI_POLL_INTERVAL_MS)
            }
            throw AssertionError(
                "visible action $actionLabel not found near field $fieldLabel; " +
                        "package=${device.currentPackageName}"
            )
        }

        fun waitForVisibleEditableField(device: UiDevice, label: String): UiObject2 {
            val deadline = SystemClock.uptimeMillis() + UI_RENDER_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                findVisibleEditableField(device, label)?.let { return it }
                scrollForward(device)
                SystemClock.sleep(UI_POLL_INTERVAL_MS)
            }
            throw AssertionError(
                "visible editable field not found: $label; package=${device.currentPackageName}"
            )
        }

        fun waitForVisibleFieldValue(
            device: UiDevice,
            label: String,
            predicate: (String) -> Boolean,
        ) {
            val deadline = SystemClock.uptimeMillis() + UI_RENDER_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                val field = findVisibleEditableField(device, label)
                if (field != null && predicate(field.text.orEmpty())) return
                SystemClock.sleep(UI_POLL_INTERVAL_MS)
            }
            // Never include editable values or clipboard material in failure diagnostics.
            throw AssertionError(
                "visible field did not show the expected update: $label; " +
                        "package=${device.currentPackageName}"
            )
        }

        fun findVisibleEditableField(device: UiDevice, label: String): UiObject2? =
            device.findObjects(By.hint(label)).firstOrNull { !it.visibleBounds.isEmpty } ?:
            device.findObjects(By.clazz(EditText::class.java))
                .firstOrNull { !it.visibleBounds.isEmpty && it.hint == label }

        fun findVisibleLabel(device: UiDevice, label: String): UiObject2? =
            findVisibleLabels(device, label).firstOrNull()

        fun findVisibleLabels(device: UiDevice, label: String): List<UiObject2> =
            sequenceOf(By.text(label), By.desc(label), By.hint(label))
                .flatMap { selector -> device.findObjects(selector).asSequence() }
                .filter { !it.visibleBounds.isEmpty }
                .distinctBy { it.visibleCenter }
                .toList()

        fun scrollForward(device: UiDevice) {
            device.findObjects(By.scrollable(true))
                .filter { !it.visibleBounds.isEmpty }
                .sortedByDescending { it.visibleBounds.width() * it.visibleBounds.height() }
                .firstOrNull { scrollable ->
                    runCatching { scrollable.scroll(Direction.DOWN, 0.65f) }
                        .getOrDefault(false)
                }
        }

        fun clickProfileEdit(device: UiDevice, remarks: String, editLabel: String) {
            val remarksObject = waitForVisibleLabel(
                device,
                remarks,
                scrollForwardWhenMissing = true,
            )
            var ancestor: UiObject2? = remarksObject
            repeat(6) {
                val current = ancestor ?: return@repeat
                current.findObject(By.desc(editLabel))?.takeIf { !it.visibleBounds.isEmpty }?.let {
                    it.click()
                    SystemClock.sleep(UI_POLL_INTERVAL_MS)
                    return
                }
                ancestor = current.parent
            }
            val nearest = device.findObjects(By.desc(editLabel))
                .filter { !it.visibleBounds.isEmpty }
                .minByOrNull {
                    kotlin.math.abs(it.visibleCenter.y - remarksObject.visibleCenter.y)
                }
            requireNotNull(nearest) { "profile edit control was not visible" }.click()
            SystemClock.sleep(UI_POLL_INTERVAL_MS)
        }
    }
}
