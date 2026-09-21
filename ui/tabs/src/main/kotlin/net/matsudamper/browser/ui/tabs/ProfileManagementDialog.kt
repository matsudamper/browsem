package net.matsudamper.browser.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.data.ProfileIcon
import net.matsudamper.browser.resources.R as ResourcesR

/**
 * プロファイル管理ダイアログ。
 * 一覧のタップで切り替え、アイコンのタップでアイコン変更、鉛筆で名前変更、下部ボタンで追加を行う。
 */
@Composable
internal fun ProfileManagementDialog(
    uiState: ProfileSwitcherUiState,
    onDismiss: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<ProfileSwitcherUiState.ProfileItem?>(null) }
    var iconTarget by remember { mutableStateOf<ProfileSwitcherUiState.ProfileItem?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("プロファイル") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                uiState.profiles.forEachIndexed { index, profile ->
                    ProfileRow(
                        profile = profile,
                        onSelect = {
                            profile.listener.onSelect()
                            onDismiss()
                        },
                        onClickIcon = { iconTarget = profile },
                        onClickRename = { renameTarget = profile },
                        modifier = Modifier.testTag(ProfileManagementTestTags.ProfileItem(index).testTag),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = uiState.callbacks::onAddProfile,
                modifier = Modifier.testTag(ProfileManagementTestTags.AddProfileButton.testTag),
            ) {
                Text("追加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        },
    )

    val renameProfile = renameTarget
    if (renameProfile != null) {
        RenameProfileDialog(
            currentName = renameProfile.name,
            onConfirm = { newName ->
                renameProfile.listener.onRename(newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    val iconProfile = iconTarget
    if (iconProfile != null) {
        ProfileIconPickerDialog(
            currentIcon = iconProfile.icon,
            onIconSelected = { icon ->
                iconProfile.listener.onChangeIcon(icon)
                iconTarget = null
            },
            onDismiss = { iconTarget = null },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: ProfileSwitcherUiState.ProfileItem,
    onSelect: () -> Unit,
    onClickIcon: () -> Unit,
    onClickRename: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = profile.isActive,
            onClick = onSelect,
        )
        IconButton(onClick = onClickIcon) {
            ProfileIconBadge(icon = profile.icon, size = 32.dp)
        }
        Text(
            text = profile.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        IconButton(onClick = onClickRename) {
            Icon(
                painter = painterResource(ResourcesR.drawable.ic_edit_24dp),
                contentDescription = "名前を変更",
            )
        }
    }
}

/** 丸い背景に載せたプロファイルアイコン。一覧とバー右端のボタンで共用する */
@Composable
internal fun ProfileIconBadge(
    icon: ProfileIcon,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon.toDrawableRes()),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

@Composable
private fun RenameProfileDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("プロファイル名を変更") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("プロファイル名") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ProfileManagementTestTags.RenameTextField.testTag),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag(ProfileManagementTestTags.RenameConfirmButton.testTag),
            ) {
                Text("変更")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

@Composable
private fun ProfileIconPickerDialog(
    currentIcon: ProfileIcon,
    onIconSelected: (ProfileIcon) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アイコンを選択") },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(ProfileIcon.entries) { icon ->
                    val isSelected = icon == currentIcon
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            .clickable { onIconSelected(icon) }
                            .padding(12.dp)
                            .testTag(ProfileManagementTestTags.IconOption(icon).testTag),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(icon.toDrawableRes()),
                            contentDescription = null,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    )
}

sealed interface ProfileManagementTestTags {
    val id: String

    val testTag get() = "${ProfileManagementTestTags::class.java.name}#$id"

    object OpenDialogButton : ProfileManagementTestTags {
        override val id: String = "open_dialog_button"
    }

    object AddProfileButton : ProfileManagementTestTags {
        override val id: String = "add_profile_button"
    }

    class ProfileItem(index: Int) : ProfileManagementTestTags {
        override val id: String = "profile_item_$index"
    }

    object RenameTextField : ProfileManagementTestTags {
        override val id: String = "rename_text_field"
    }

    object RenameConfirmButton : ProfileManagementTestTags {
        override val id: String = "rename_confirm_button"
    }

    class IconOption(icon: ProfileIcon) : ProfileManagementTestTags {
        override val id: String = "icon_option_${icon.name}"
    }
}

private object PreviewProfileListener : ProfileSwitcherUiState.ProfileItem.Listener {
    override fun onSelect() = Unit
    override fun onRename(newName: String) = Unit
    override fun onChangeIcon(icon: ProfileIcon) = Unit
}

internal val PreviewProfileSwitcherUiState = ProfileSwitcherUiState(
    activeProfileIcon = ProfileIcon.PERSON,
    profiles = listOf(
        ProfileSwitcherUiState.ProfileItem("デフォルト", ProfileIcon.PERSON, true, PreviewProfileListener),
        ProfileSwitcherUiState.ProfileItem("仕事", ProfileIcon.WORK, false, PreviewProfileListener),
        ProfileSwitcherUiState.ProfileItem("買い物", ProfileIcon.SHOPPING_CART, false, PreviewProfileListener),
    ),
    callbacks = object : ProfileSwitcherUiState.Callbacks {
        override fun onAddProfile() = Unit
    },
)

@Composable
@Preview
private fun PreviewProfileManagementDialog() {
    ProfileManagementDialog(
        uiState = PreviewProfileSwitcherUiState,
        onDismiss = {},
    )
}

@Composable
@Preview
private fun PreviewProfileIconPickerDialog() {
    ProfileIconPickerDialog(
        currentIcon = ProfileIcon.WORK,
        onIconSelected = {},
        onDismiss = {},
    )
}
