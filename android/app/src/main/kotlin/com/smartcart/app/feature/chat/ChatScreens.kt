package com.smartcart.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartcart.app.feature.home.categoryIcon
import com.smartcart.app.R
import com.smartcart.app.ui.common.ErrorCopy
import com.smartcart.app.ui.common.labelRes
import com.smartcart.app.ui.common.title
import androidx.compose.ui.res.stringResource
import com.smartcart.app.ui.common.Format
import com.smartcart.app.ui.common.Load
import com.smartcart.app.ui.common.LoadContent
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.app.ui.common.ScreenScaffold
import com.smartcart.app.ui.common.toLoad
import com.smartcart.app.AppContainer
import com.smartcart.data.AnalyticsEventName
import com.smartcart.data.ApiResult
import com.smartcart.designsystem.AppCard
import com.smartcart.designsystem.Banner
import com.smartcart.designsystem.EmptyState
import com.smartcart.designsystem.PrimaryButton
import com.smartcart.designsystem.Spacing
import com.smartcart.designsystem.Tone
import com.smartcart.model.ApiError
import com.smartcart.model.AssistantTurn
import com.smartcart.model.CartDraft
import com.smartcart.model.Category
import com.smartcart.model.ChatSession
import com.smartcart.model.NextStep
import com.smartcart.model.Objective
import com.smartcart.model.ProviderStatus
import com.smartcart.model.QuoteRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ChatItem {
    val id: String
    data class User(override val id: String, val text: String) : ChatItem
    data class Assistant(override val id: String, val turn: AssistantTurn) : ChatItem
    data class Failure(override val id: String, val error: ApiError, val retryText: String) : ChatItem
}

data class ChatUiState(
    val sessionId: String? = null,
    val items: List<ChatItem> = emptyList(),
    val sending: Boolean = false,
    val draft: CartDraft? = null,
    val objective: Objective = Objective.LOWEST_TOTAL,
    val comparing: Boolean = false,
    val compareError: ApiError? = null,
    val sessionError: ApiError? = null,
)

class ChatViewModel(private val c: AppContainer, val category: Category) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state
    private var counter = 0

    init { startSession() }

    fun startSession() {
        viewModelScope.launch {
            when (val r = c.chat.createSession(category)) {
                is ApiResult.Success -> _state.update { it.copy(sessionId = r.value.sessionId, sessionError = null) }
                is ApiResult.Failure -> _state.update { it.copy(sessionError = r.error) }
            }
        }
    }

    fun send(text: String, clarification: Pair<String, String>? = null) {
        val sid = _state.value.sessionId ?: return
        if (text.isBlank() || _state.value.sending) return
        _state.update { it.copy(items = it.items.filterNot { i -> i is ChatItem.Failure } + ChatItem.User("u${counter++}", text.trim()), sending = true) }
        c.analytics.track(if (clarification != null) AnalyticsEventName.CHAT_CLARIFICATION_ANSWERED else AnalyticsEventName.CHAT_MESSAGE_SENT, mapOf("category" to category.key))
        viewModelScope.launch {
            when (val r = c.chat.send(sid, category, text.trim(), clarification)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        items = it.items + ChatItem.Assistant(r.value.messageId, r.value),
                        sending = false,
                        draft = r.value.cartDraft ?: it.draft,
                    )
                }
                is ApiResult.Failure -> _state.update { it.copy(items = it.items + ChatItem.Failure("f${counter++}", r.error, text), sending = false) }
            }
        }
    }

    fun retry(item: ChatItem.Failure) {
        _state.update { s -> s.copy(items = s.items.filterNot { it.id == item.id }.dropLastWhile { it is ChatItem.User }) }
        send(item.retryText)
    }

    fun setQuantity(lineId: String, qty: Int) = _state.update { s ->
        val d = s.draft ?: return@update s
        s.copy(draft = d.copy(lines = d.lines.map { if (it.lineId == lineId) it.copy(quantity = qty.coerceIn(1, 50)) else it }))
    }

    fun removeLine(lineId: String) = _state.update { s ->
        val d = s.draft ?: return@update s
        s.copy(draft = d.copy(lines = d.lines.filterNot { it.lineId == lineId }))
    }

    fun setObjective(o: Objective) = _state.update { it.copy(objective = o) }

    fun compare(onQuote: (String) -> Unit) {
        val d = _state.value.draft ?: return
        if (_state.value.comparing) return
        val eligible = c.providers.providers.value.filter { it.category == category && !it.hidden && it.status != ProviderStatus.DISABLED }.map { it.key }
        _state.update { it.copy(comparing = true, compareError = null) }
        c.analytics.track(AnalyticsEventName.COMPARISON_REQUESTED, mapOf("category" to category.key, "line_count" to d.lines.size, "objective" to _state.value.objective))
        viewModelScope.launch {
            val req = QuoteRequest(category, d.lines, d.ride, eligible, d.selectedProvider, _state.value.objective, _state.value.sessionId)
            when (val r = c.comparisons.create(req)) {
                is ApiResult.Success -> { _state.update { it.copy(comparing = false) }; onQuote(r.value) }
                is ApiResult.Failure -> _state.update { it.copy(comparing = false, compareError = r.error) }
            }
        }
    }
}

private fun placeholder(c: Category) = when (c) {
    Category.TRAVEL -> R.string.chat_placeholder_travel
    Category.FOOD -> R.string.chat_placeholder_food
    Category.GROCERY -> R.string.chat_placeholder_grocery
    Category.SHOPPING -> R.string.chat_placeholder_shopping
}

// ---------------------------------------------------------------- S-CHAT-01
@Composable
fun ChatScreen(category: Category, onBack: () -> Unit, onComparison: (String) -> Unit) {
    val c = LocalAppContainer.current
    val vm = viewModel(key = "chat-${category.key}") { ChatViewModel(c, category) }
    val s by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(s.items.size, s.draft) { if (s.items.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    ScreenScaffold(
        title = category.title(),
        onBack = onBack,
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.take(2000) },
                        placeholder = { Text(stringResource(placeholder(category))) },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { vm.send(input); input = "" }),
                    )
                    IconButton(
                        onClick = { vm.send(input); input = "" },
                        enabled = input.isNotBlank() && !s.sending && s.sessionId != null,
                        modifier = Modifier.size(Spacing.touchTarget),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send)) }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding() + Spacing.sm, bottom = padding.calculateBottomPadding() + Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(categoryIcon(category), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(Spacing.sm))
                    Text(
                        stringResource(R.string.chat_intro, category.title().lowercase()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            s.sessionError?.let { e -> item { Banner(ErrorCopy.body(e), tone = Tone.DANGER, title = ErrorCopy.title(e), actionLabel = stringResource(R.string.common_try_again), onAction = vm::startSession) } }
            items(s.items, key = { it.id }) { item ->
                when (item) {
                    is ChatItem.User -> Bubble(item.text, mine = true)
                    is ChatItem.Assistant -> AssistantBubble(item.turn, onOption = { id, label -> vm.send(label, item.turn.clarification!!.clarificationId to id) })
                    is ChatItem.Failure -> Banner(ErrorCopy.body(item.error), tone = Tone.DANGER, title = ErrorCopy.title(item.error), actionLabel = stringResource(R.string.common_retry), onAction = { vm.retry(item) })
                }
            }
            if (s.sending) item { Bubble(stringResource(R.string.chat_thinking), mine = false, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            s.draft?.let { d ->
                val last = s.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
                if (last?.turn?.nextStep == NextStep.COMPARE || last?.turn?.nextStep == NextStep.REVIEW_CART) {
                    item(key = "draft") {
                        DraftCard(d, s, onQty = vm::setQuantity, onRemove = vm::removeLine, onObjective = vm::setObjective, onCompare = { vm.compare(onComparison) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, modifier: Modifier = Modifier) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            modifier
                .widthIn(max = 320.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    MaterialTheme.shapes.medium,
                )
                .padding(Spacing.md),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun AssistantBubble(turn: AssistantTurn, onOption: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (turn.degraded) Banner(stringResource(R.string.chat_degraded), tone = Tone.WARNING)
        Bubble(turn.replyText, mine = false)
        turn.clarification?.let { cl ->
            Text(cl.question, style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                cl.options.forEach { o -> AssistChip(onClick = { onOption(o.optionId, o.label) }, label = { Text(o.label) }, modifier = Modifier.heightIn(min = Spacing.touchTarget)) }
            }
        }
    }
}

@Composable
private fun DraftCard(
    d: CartDraft,
    s: ChatUiState,
    onQty: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onObjective: (Objective) -> Unit,
    onCompare: () -> Unit,
) {
    AppCard {
        Text(stringResource(if (d.ride != null) R.string.chat_your_ride else R.string.chat_your_cart), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(Spacing.xs))
        d.ride?.let { r ->
            com.smartcart.designsystem.KeyValueRow(stringResource(R.string.chat_pickup), r.pickupLabel)
            com.smartcart.designsystem.KeyValueRow(stringResource(R.string.chat_drop), r.dropLabel)
            com.smartcart.designsystem.KeyValueRow(stringResource(R.string.chat_ride_type), stringResource(r.rideClass.labelRes()))
            com.smartcart.designsystem.KeyValueRow(stringResource(R.string.chat_when), r.`when`?.let { Format.dateTime(it) } ?: stringResource(R.string.chat_now))
        }
        d.lines.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onQty(l.lineId, l.quantity - 1) }, enabled = l.quantity > 1) { Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.chat_decrease, l.name)) }
                val qtyCd = stringResource(R.string.chat_quantity_cd, l.quantity)
                Text(l.quantity.toString(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { contentDescription = qtyCd })
                IconButton(onClick = { onQty(l.lineId, l.quantity + 1) }) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.chat_increase, l.name)) }
                IconButton(onClick = { onRemove(l.lineId) }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.chat_remove, l.name)) }
            }
        }
        Spacer(Modifier.size(Spacing.sm))
        Text(stringResource(R.string.chat_compare_by), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            listOf(Objective.LOWEST_TOTAL, Objective.FASTEST, Objective.BEST_VALUE).forEach { o ->
                FilterChip(selected = s.objective == o, onClick = { onObjective(o) }, label = { Text(stringResource(o.labelRes())) }, modifier = Modifier.heightIn(min = Spacing.touchTarget))
            }
        }
        s.compareError?.let { Banner(ErrorCopy.body(it), tone = Tone.DANGER, title = ErrorCopy.title(it)) }
        Spacer(Modifier.size(Spacing.sm))
        PrimaryButton(
            stringResource(if (d.ride != null) R.string.chat_compare_fares else R.string.chat_compare_cart),
            onClick = onCompare,
            loading = s.comparing,
            enabled = d.lines.isNotEmpty() || d.ride != null,
        )
        Text(stringResource(R.string.chat_nothing_until_approved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- S-CHAT-02
class ChatHistoryViewModel(private val c: AppContainer) : ViewModel() {
    val state = MutableStateFlow<Load<List<ChatSession>>>(Load.Loading)
    init { load() }
    fun load() { state.value = Load.Loading; viewModelScope.launch { state.value = c.chat.sessions().toLoad() } }
    fun delete(id: String) = viewModelScope.launch { c.chat.deleteSession(id); load() }
}

@Composable
fun ChatHistoryScreen(onBack: () -> Unit, onOpen: (Category) -> Unit) {
    val c = LocalAppContainer.current
    val vm = viewModel { ChatHistoryViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    ScreenScaffold(stringResource(R.string.chat_history_title), onBack) { padding ->
        LoadContent(state, onRetry = vm::load) {
            val sessions = (state as Load.Ready).value
            if (sessions.isEmpty()) {
                EmptyState(stringResource(R.string.chat_history_empty_title), stringResource(R.string.chat_history_empty_body), Modifier.padding(padding))
                return@LoadContent
            }
            LazyColumn(contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = padding.calculateTopPadding()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items(sessions, key = { it.sessionId }) { s ->
                    AppCard(onClick = { onOpen(s.category) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(categoryIcon(s.category), contentDescription = null)
                            Spacer(Modifier.size(Spacing.md))
                            Column(Modifier.weight(1f)) {
                                Text(s.title, style = MaterialTheme.typography.titleSmall)
                                Text(Format.dateTime(s.updatedAt ?: s.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.delete(s.sessionId) }) { Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.chat_history_delete, s.title)) }
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.chat_history_retention), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = Spacing.md))
                }
            }
        }
    }
}
