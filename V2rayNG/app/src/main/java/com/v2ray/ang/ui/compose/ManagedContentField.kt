package com.v2ray.ang.ui.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.v2ray.ang.R

/** Text content editor with explicit reveal, copy, and file-content import affordances. */
@Composable
fun ManagedContentField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onCopyClick: () -> Unit,
    onImportClick: (() -> Unit)? = null,
    sensitive: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 5,
    textStyle: TextStyle = TextStyle.Default,
) {
    var revealed by rememberSaveable { mutableStateOf(!sensitive) }

    FormTextField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = textStyle,
        visualTransformation = if (sensitive && !revealed) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = {
            Row {
                if (sensitive) {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            painter = painterResource(
                                if (revealed) R.drawable.ic_visibility_off_24dp
                                else R.drawable.ic_visibility_24dp
                            ),
                            contentDescription = stringResource(
                                if (revealed) R.string.acc_hide_content
                                else R.string.acc_show_content
                            ),
                        )
                    }
                }
                IconButton(
                    onClick = onCopyClick,
                    enabled = value.isNotEmpty(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = stringResource(R.string.acc_copy_content),
                    )
                }
                if (onImportClick != null) {
                    IconButton(onClick = onImportClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_file_24dp),
                            contentDescription = stringResource(R.string.acc_import_content),
                        )
                    }
                }
            }
        },
    )
}
