package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.auth.GoogleAccountSession
import com.music.bitchord.auth.YouTubeProfile
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.theme.SystemBarIcons
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/** Shared compact selector for every visible YouTube account avatar. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AccountProfileSelector(
    accounts: List<GoogleAccountSession>,
    activeAccountId: String?,
    activeProfileId: String?,
    hazeState: HazeState,
    onSelect: (GoogleAccountSession, YouTubeProfile) -> Unit,
    onAddAccount: () -> Unit,
    onRemoveAccount: (GoogleAccountSession) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var managing by remember { mutableStateOf(false) }
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(28.dp)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shape,
            modifier = Modifier
                .padding(top = 16.dp, start = 20.dp, end = 20.dp)
                .fillMaxWidth()
                .clip(shape)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.20f),
                    shape = shape,
                )
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.ultraThin(MaterialTheme.colorScheme.surface.copy(alpha = 0.60f)),
                        )
                    },
                )
                .clickable(onClick = {}),
        ) {
            LazyColumn(
                modifier = Modifier.padding(vertical = 12.dp)
            ) {
                item {
                    Text(
                        text = stringResource(R.string.switch_account),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.W800,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }

                accounts.forEach { account ->
                    item {
                        Text(
                            text = account.email.ifBlank { account.name.ifBlank { stringResource(R.string.accounts) } },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.W600,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(account.profiles.size) { index ->
                        val profile = account.profiles[index]
                        ProfileRow(
                            profile = profile,
                            selected = account.accountId == activeAccountId && profile.profileId == activeProfileId,
                            managing = managing,
                            onClick = { onSelect(account, profile); onDismiss() },
                            onRemove = { onRemoveAccount(account) },
                        )
                    }
                }

                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )
                }

                item {
                    SelectorAction(
                        icon = Icons.Rounded.Add,
                        label = stringResource(R.string.add_account),
                        onClick = onAddAccount,
                    )
                }
                item {
                    SelectorAction(
                        icon = Icons.Rounded.ManageAccounts,
                        label = stringResource(R.string.manage_accounts),
                        onClick = { managing = !managing },
                    )
                }
                item {
                    SelectorAction(
                        icon = Icons.Rounded.Settings,
                        label = stringResource(R.string.settings),
                        onClick = onOpenSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: YouTubeProfile,
    selected: Boolean,
    managing: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val description = stringResource(if (selected) R.string.selected_account else R.string.switch_account)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(role = Role.RadioButton, onClick = if (managing) onRemove else onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (profile.avatar != null) {
            AsyncImage(
                model = profile.avatar,
                contentDescription = null,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = profile.handle.ifBlank { stringResource(if (profile.isBrandAccount) R.string.brand_account else R.string.personal) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (selected && !managing) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.selected_account),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        if (managing) {
            Text(
                text = stringResource(R.string.sign_out),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.W700,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SelectorAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.W600,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
