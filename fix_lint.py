with open('app/src/main/java/com/omnidev/workspace/ui/settings/AISettingsScreen.kt', 'r') as f:
    content = f.read()

content = content.replace("val providersUiState by providersViewModel?.uiState?.collectAsState() ?: mutableStateOf(null)", "val providersUiState by providersViewModel?.uiState?.collectAsState() ?: remember { mutableStateOf(null) }")

with open('app/src/main/java/com/omnidev/workspace/ui/settings/AISettingsScreen.kt', 'w') as f:
    f.write(content)
