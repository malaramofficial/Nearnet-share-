package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.i18n.AppStrings
import com.example.ui.screens.RemoteShareScreen
import com.example.ui.theme.LocalSignalColors
import com.example.ui.theme.NetSetuTheme
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      NetSetuTheme {
        NetSetuApp()
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetSetuApp(viewModel: MainViewModel = viewModel()) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val strings = AppStrings.forLanguage(uiState.settings.language)
  val signalColors = LocalSignalColors.current

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Box(
              modifier =
                Modifier.size(10.dp)
                  .clip(CircleShape)
                  .background(
                    if (uiState.proxyMetrics.isRunning) signalColors.success
                    else MaterialTheme.colorScheme.primary
                  )
            )
            Text(
              text = strings.appName,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
            )
          }
        },
        actions = {
          FilledTonalButton(
            onClick = { viewModel.toggleLanguage() },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier =
              Modifier.padding(end = 8.dp)
                .minimumInteractiveComponentSize()
                .testTag("language_toggle_btn"),
          ) {
            Icon(
              imageVector = Icons.Filled.Translate,
              contentDescription = "Switch Language",
              modifier = Modifier.size(16.dp),
            )
            Text(
              text = " ${strings.langToggleLabel}",
              style = MaterialTheme.typography.labelLarge,
            )
          }
        },
        colors =
          TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
          ),
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Box(modifier = Modifier.widthIn(max = 680.dp).fillMaxSize()) {
        RemoteShareScreen(
          uiState = uiState,
          strings = strings,
          viewModel = viewModel,
          contentPadding = PaddingValues(16.dp),
        )
      }
    }
  }
}
