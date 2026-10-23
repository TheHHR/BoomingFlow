/*
 * Copyright (c) 2025 Christians Martínez Alvarado
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mardous.booming.ui.screen.about

import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import com.mardous.booming.App
import com.mardous.booming.BuildConfig
import com.mardous.booming.R
import com.mardous.booming.core.model.about.AboutItemData
import com.mardous.booming.core.model.about.loadTranslators
import com.mardous.booming.extensions.MIME_TYPE_PLAIN_TEXT
import com.mardous.booming.extensions.languageFlagEmoji
import com.mardous.booming.extensions.languageNameInEnglish
import com.mardous.booming.extensions.openUrl
import com.mardous.booming.extensions.toChooser
import com.mardous.booming.extensions.tryStartActivity
import com.mardous.booming.ui.component.compose.CollapsibleAppBarScaffold
import com.mardous.booming.ui.component.compose.ShapedText
import com.mardous.booming.util.Constants.DOWNLOAD_URL
import com.mardous.booming.util.Preferences
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import dev.jeziellago.compose.markdowntext.MarkdownText

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AboutScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current

    val appVersion = try {
        val packageManager = context.packageManager
        val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName
        if (BuildConfig.FLAVOR == "fdroid") {
            "$versionName (F-Droid)"
        } else versionName ?: "Unknown"
    } catch (_: PackageManager.NameNotFoundException) {
        "Unknown"
    }

    var showTranslatorsDialog by remember { mutableStateOf(false) }
    val translators by produceState(emptyList()) {
        value = loadTranslators(context).map { (tag, translators) ->
            val flag = tag.languageFlagEmoji(context)
            AboutItemData(
                icon = { AboutItemIcon(flag) },
                title = tag.languageNameInEnglish(),
                markdown = translators,
                onClick = {}
            )
        }
    }

    if (showTranslatorsDialog) {
        ModalBottomSheet(onDismissRequest = { showTranslatorsDialog = false}) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
            ) {
                itemsIndexed(translators) { index, item ->
                    AboutListItem(
                        index = index,
                        itemCount = translators.size,
                        data = item,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .8f)
                    )
                }
            }
        }
    }

    var showLicensesDialog by remember { mutableStateOf(false) }
    val libraries by produceLibraries(R.raw.aboutlibraries)
    if (showLicensesDialog) {
        ModalBottomSheet(onDismissRequest = { showLicensesDialog = false}) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                LibrariesContainer(
                    libraries = libraries,
                    licenseDialogConfirmText = stringResource(R.string.close_action),
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                )
            }
        }
    }

    val sections = getAboutSections(
        onTranslatorsClick = { showTranslatorsDialog = true },
        onLicensesClick = { showLicensesDialog = true }
    )

    CollapsibleAppBarScaffold(
        title = stringResource(R.string.about_title),
        headerMode = Preferences.appBarMode,
        onBackClick = onBackClick
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
        ) {
            item { BoomingMusicHeader(version = appVersion) }

            for (section in sections) {
                item { AboutSectionTitle(section.first) }
                itemsIndexed(section.second) { index, item ->
                    AboutListItem(
                        index = index,
                        itemCount = section.second.size,
                        data = item
                    )
                }
            }
        }
    }
}

@Composable
private fun BoomingMusicHeader(version: String) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            shape = CircleShape,
            color = LocalContentColor.current.copy(alpha = 0.1f)
        ) {
            Icon(
                painter = painterResource(R.drawable.boomingmusic_logo_monochrome),
                tint = LocalContentColor.current,
                contentDescription = null,
                modifier = Modifier.size(88.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_title),
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 1
        )
        Text(
            text = stringResource(R.string.app_description),
            fontStyle = FontStyle.Italic,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        ShapedText(
            text = stringResource(R.string.app_version_x, version),
            style = MaterialTheme.typography.bodySmall,
            shape = CircleShape
        )
        if (BuildConfig.IS_CI_BUILD) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_construction_24dp),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "CI Build",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AboutSectionTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp, start = 16.dp)
    )
}

@Composable
private fun AboutItemIcon(
    icon: Painter,
    modifier: Modifier = Modifier
) {
    AboutItemIconSurface(modifier) {
        Icon(
            painter = icon,
            contentDescription = null
        )
    }
}

@Composable
private fun AboutItemIcon(
    emoji: String,
    modifier: Modifier = Modifier
) {
    AboutItemIconSurface(modifier) {
        Text(
            text = emoji,
            fontSize = 24.sp,
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

@Composable
private fun AboutItemIconSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.size(48.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

@Composable
private fun getAboutSections(
    onTranslatorsClick: () -> Unit,
    onLicensesClick: () -> Unit
): List<Pair<String, List<AboutItemData>>> {
    val context = LocalContext.current

    val sendInvitationTitle = stringResource(R.string.send_invitation_message)
    val invitationMessage = stringResource(R.string.invitation_message_content, DOWNLOAD_URL)

    return listOf(
        stringResource(R.string.support_development) to listOf(
            AboutItemData(
                icon = { AboutItemIcon(painterResource(R.drawable.ic_share_24dp)) },
                title = stringResource(R.string.share_app),
                summary = stringResource(R.string.share_app_summary),
                onClick = {
                    context.tryStartActivity(
                        Intent(Intent.ACTION_SEND)
                            .putExtra(Intent.EXTRA_TEXT, invitationMessage)
                            .setType(MIME_TYPE_PLAIN_TEXT)
                            .toChooser(sendInvitationTitle)
                    )
                }
            ),
            AboutItemData(
                icon = { AboutItemIcon(painterResource(R.drawable.ic_translate_24dp)) },
                title = stringResource(R.string.translators_title),
                summary = stringResource(R.string.translators_summary),
                onClick = onTranslatorsClick
            )
        ),
        stringResource(R.string.legal) to listOf(
            AboutItemData(
                icon = { AboutItemIcon(painterResource(R.drawable.ic_description_24dp)) },
                title = stringResource(R.string.licenses),
                onClick = onLicensesClick
            )
        )
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AboutListItem(
    index: Int,
    itemCount: Int,
    data: AboutItemData,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer
) {
    SegmentedListItem(
        onClick = data.onClick,
        shapes = ListItemDefaults.segmentedShapes(index, itemCount),
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = {
            data.icon()
        },
        colors = ListItemDefaults.segmentedColors(containerColor = containerColor),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Text(
                text = data.title,
                style = MaterialTheme.typography.titleMedium,
                overflow = TextOverflow.Ellipsis
            )
            if (!data.markdown.isNullOrBlank()) {
                MarkdownText(
                    markdown = data.markdown,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                    )
                )
            } else if (!data.summary.isNullOrBlank()) {
                Text(
                    text = data.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.copy(alpha = 0.8f),
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
