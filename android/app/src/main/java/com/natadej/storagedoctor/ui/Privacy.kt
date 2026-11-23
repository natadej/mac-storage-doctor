package com.natadej.storagedoctor.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun PrivacyButton() {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }) { Text("Privacy policy") }
    if (show) AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("Storage Doctor privacy policy") },
        text = {
            Text(
                "Developer: natadej. Updated 2026-11-23.\n\n" +
                    "Storage Doctor processes file names, paths, sizes, dates and file contents for duplicate comparison only on this phone. All files access enables shared-folder analysis, browsing and user-confirmed maintenance; it cannot access other apps' private files. No files are deleted without your confirmation.\n\n" +
                    "Optional Usage access reads sizes of packages Android makes visible to the app. Settings and up to 20 scan snapshots (folder paths/sizes, visible-app package names/sizes and timestamps) are kept in private local storage. Automatic app backup is disabled. Clear the app's storage in Android Settings or uninstall it to remove that history and settings. Your shared files are not removed by uninstalling.\n\n" +
                    "The optional background check reads total/free space and checks your target every six hours, subject to Android scheduling. It does not scan your files. Notifications are optional.\n\n" +
                    "The app has no network access, accounts, ads, analytics or cloud service. It does not upload, sell or automatically share your data. When you choose Open, the viewer you choose receives read access to that file; its own privacy practices apply.\n\n" +
                    "You can decline access and use storage totals, or revoke file/usage/notification access in Android Settings. Contact the developer through https://github.com/natadej/mac-storage-doctor/issues; do not post private file paths or contents publicly.",
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = { show = false }) { Text("Close") } },
    )
}
