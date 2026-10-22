package com.example.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The settings screen. It offers the five themes and a light/dark switch, each
 * theme showing a swatch of its own colour, and it holds the git account and
 * the projects attached to the folders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTheme: AppTheme,
    useDark: Boolean,
    onThemeChange: (AppTheme) -> Unit,
    onDarkChange: (Boolean) -> Unit,
    // ---- git ----
    signedInName: String?,
    signedInEmail: String?,
    folders: List<Folder>,
    projectOf: (String) -> GitProject?,
    onSignIn: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onAttachProject: (folderId: String, project: GitProject) -> Unit,
    onDetachProject: (folderId: String) -> Unit,
    onInitRepository: (folderId: String) -> Unit,
    onCloneInto: (folderId: String, remoteUrl: String) -> Unit,
    onCreateRepo: (folderId: String, name: String, isPrivate: Boolean, description: String) -> Unit,
    onBack: () -> Unit,
) {
    var showSignIn by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var showClone by remember { mutableStateOf(false) }
    var attachTarget by remember { mutableStateOf<Folder?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ---- the git account ----
            Text(
                text = "GitHub account",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
            )

            if (signedInName.isNullOrBlank()) {
                Text(
                    text = "Sign in with a personal access token to create and push " +
                        "repositories. The token is kept encrypted on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                TextButton(
                    onClick = { showSignIn = true },
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                ) {
                    Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Connect GitHub")
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text(text = signedInName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = signedInEmail.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onSignOut) { Text("Sign out") }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- the projects of the folders ----
            Text(
                text = "Git projects",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
            Text(
                text = "Each folder can be its own git project, so different " +
                    "folders can point at different repositories.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )

            folders.forEach { folder ->
                ProjectRow(
                    folder = folder,
                    project = projectOf(folder.id),
                    onAttach = {
                        attachTarget = folder
                        showAttach = true
                    },
                    onDetach = { onDetachProject(folder.id) },
                    onInit = { onInitRepository(folder.id) },
                    onClone = {
                        attachTarget = folder
                        showClone = true
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- the theme ----
            Text(
                text = "Theme",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )

            AppTheme.entries.forEach { theme ->
                ThemeRow(
                    theme = theme,
                    selected = theme == currentTheme,
                    onClick = { onThemeChange(theme) },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDarkChange(!useDark) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Dark variant", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (useDark) "Using the dark version of ${currentTheme.displayName}"
                        else "Using the light version of ${currentTheme.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = useDark, onCheckedChange = onDarkChange)
            }
        }
    }

    if (showSignIn) {
        SignInDialog(
            onDismiss = { showSignIn = false },
            onConfirm = { token, email ->
                onSignIn(token, email)
                showSignIn = false
            },
        )
    }

    if (showAttach && attachTarget != null) {
        AttachProjectDialog(
            folderName = attachTarget?.displayName().orEmpty(),
            onDismiss = { showAttach = false },
            onCreate = { name, isPrivate, description ->
                val folder = attachTarget
                showAttach = false
                if (folder != null) {
                    onCreateRepo(folder.id, name, isPrivate, description)
                }
            },
        )
    }

    if (showClone && attachTarget != null) {
        CloneDialog(
            folderName = attachTarget?.displayName().orEmpty(),
            onDismiss = { showClone = false },
            onConfirm = { url ->
                val folder = attachTarget
                showClone = false
                if (folder != null) onCloneInto(folder.id, url)
            },
        )
    }
}

/** One folder in the list of git projects. */
@Composable
private fun ProjectRow(
    folder: Folder,
    project: GitProject?,
    onAttach: () -> Unit,
    onDetach: () -> Unit,
    onInit: () -> Unit,
    onClone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.displayName(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = project?.name ?: "No project yet",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (project == null) {
            TextButton(onClick = onAttach) { Text("Attach") }
            TextButton(onClick = onInit) { Text("New repo") }
            TextButton(onClick = onClone) { Text("Clone") }
        } else {
            TextButton(onClick = onDetach) { Text("Detach") }
        }
    }
}

/** Asks for the personal access token and the commit email. */
@Composable
private fun SignInDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("muffyworld365@gmail.com") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect GitHub") },
        text = {
            Column {
                Text(
                    text = "Paste a personal access token. It needs the \"repo\" " +
                        "permission. The token is encrypted and kept only on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email for commits") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(token.trim(), email.trim()) },
                enabled = token.isNotBlank(),
            ) { Text("Connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Lets the user create a new repository on GitHub and attach it to a folder. */
@Composable
private fun AttachProjectDialog(
    folderName: String,
    onDismiss: () -> Unit,
    onCreate: (String, Boolean, String) -> Unit,
) {
    var name by remember { mutableStateOf(folderName.replace(" ", "-").lowercase()) }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New repository") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Repository name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Private", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), isPrivate, description.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Asks for the address of a repository to clone into a folder. */
@Composable
private fun CloneDialog(
    folderName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone into '$folderName'") },
        text = {
            Column {
                Text(
                    text = "Paste the address of the repository.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Repository address") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.isNotBlank(),
            ) { Text("Clone") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ThemeRow(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThemeSwatch(theme = theme)
        Text(
            text = theme.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * A circle split down the middle: the theme's light variant on the left, its dark
 * variant on the right, so both can be told apart at a glance.
 */
@Composable
private fun ThemeSwatch(theme: AppTheme) {
    val light = theme.lightScheme().primary
    val dark = theme.darkScheme().primary
    val outline = MaterialTheme.colorScheme.outline

    Canvas(modifier = Modifier.size(30.dp)) {
        val radius = size.minDimension / 2f
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)

        drawArc(
            color = light,
            startAngle = 90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
        )
        drawArc(
            color = dark,
            startAngle = 270f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
        )
        drawCircle(
            color = outline,
            radius = radius,
            center = center,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
        )
    }
}
