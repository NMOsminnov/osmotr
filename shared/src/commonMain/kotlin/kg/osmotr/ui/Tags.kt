package kg.osmotr.ui

import androidx.compose.ui.Modifier

/**
 * Метки testTag — ещё и resource-id (Android): обход на эмуляторе (tools/matrix) находит по ним
 * блоки. У диалога своё окно, и метка корня главного окна туда не доходит — ставится и в нём.
 */
expect fun Modifier.exposeTags(): Modifier
