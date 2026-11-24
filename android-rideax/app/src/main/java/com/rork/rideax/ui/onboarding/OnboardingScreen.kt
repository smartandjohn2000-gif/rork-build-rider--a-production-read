package com.rork.rideax.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.rideax.data.AuthProvider
import com.rork.rideax.data.Haptics
import com.rork.rideax.model.AppRole
import com.rork.rideax.ui.components.BrandBackdrop
import com.rork.rideax.ui.components.BrandIcons
import com.rork.rideax.ui.components.BrandMark
import com.rork.rideax.ui.components.BrandWordmark
import com.rork.rideax.ui.components.IconTile
import com.rork.rideax.ui.components.PrimaryButton
import com.rork.rideax.ui.components.rideCard
import com.rork.rideax.ui.components.roadCurve
import com.rork.rideax.ui.session.SessionUiState
import com.rork.rideax.ui.theme.RideColors
import com.rork.rideax.ui.theme.RideDimens
import kotlinx.coroutines.launch

private data class Highlight(val icon: ImageVector, val title: String, val detail: String)

private data class OnboardingPage(val title: String, val subtitle: String, val highlights: List<Highlight>)

private val Pages = listOf(
    OnboardingPage(
        "A ride, wherever you are",
        "Set your pickup, pick a destination and see the fare before you book.",
        listOf(
            Highlight(Icons.Rounded.MyLocation, "Instant pickup", "We detect where you are and find the nearest car."),
            Highlight(Icons.Rounded.EuroSymbol, "Upfront pricing", "The fare you see is the fare you pay."),
        ),
    ),
    OnboardingPage(
        "Follow every metre",
        "Watch your driver approach live, message them, and share your trip with someone you trust.",
        listOf(
            Highlight(Icons.Rounded.Sensors, "Live tracking", "Real-time driver location and arrival countdown."),
            Highlight(Icons.Rounded.Shield, "Safety built in", "Trip sharing and emergency help, one tap away."),
        ),
    ),
    OnboardingPage("Get moving in seconds", "Book a ride in your city, or earn on your own schedule.", emptyList()),
)

/** Three-step first-run experience that ends with the rider / driver choice. */
@Composable
fun OnboardingScreen(
    state: SessionUiState,
    onAuthenticate: (email: String, password: String, fullName: String?, isSignUp: Boolean, role: AppRole) -> Unit,
    onSignIn: (AuthProvider, AppRole) -> Unit,
    onClearError: () -> Unit,
) {
    val pager = rememberPagerState { Pages.size }
    val scope = rememberCoroutineScope()
    var role by rememberSaveable { mutableStateOf(AppRole.RIDER) }
    var showsAuth by rememberSaveable { mutableStateOf(false) }
    val isLast = pager.currentPage == Pages.lastIndex

    Box(Modifier.fillMaxSize()) {
        BrandBackdrop()
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BrandMark(markSize = 72.dp)
                BrandWordmark(fontSize = 40.sp)
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
                val page = Pages[index]
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = RideDimens.Margin),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(20.dp))
                    Text(page.title, style = MaterialTheme.typography.headlineMedium, color = RideColors.TextPrimary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        page.subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = RideColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(22.dp))
                    if (index == Pages.lastIndex) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppRole.entries.forEach { option ->
                                RoleCard(option, option == role, Modifier.weight(1f)) {
                                    role = option
                                    Haptics.tap()
                                }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            page.highlights.forEach { HighlightRow(it) }
                        }
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = RideDimens.Margin).padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(Pages.size) { index ->
                        val width by animateDpAsState(if (index == pager.currentPage) 22.dp else 8.dp, spring(dampingRatio = 0.8f), label = "dot")
                        val color by animateColorAsState(
                            if (index == pager.currentPage) RideColors.Accent else RideColors.TextSecondary.copy(alpha = 0.35f),
                            label = "dot-color",
                        )
                        Box(Modifier.size(width = width, height = 8.dp).clip(CircleShape).background(color))
                    }
                }

                PrimaryButton(
                    text = if (isLast) role.ctaTitle else "Continue",
                    onClick = {
                        if (isLast) showsAuth = true else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    },
                )

                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = RideColors.TextSecondary)) { append("Already have an account?  ") }
                        withStyle(SpanStyle(color = RideColors.Accent, fontWeight = FontWeight.SemiBold)) { append("Sign in") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) {
                            Haptics.tap()
                            showsAuth = true
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }
    }

    if (showsAuth) {
        AuthSheet(
            role = role,
            state = state,
            onAuthenticate = { email, password, fullName, isSignUp -> onAuthenticate(email, password, fullName, isSignUp, role) },
            onSignIn = { provider -> onSignIn(provider, role) },
            onDismiss = {
                showsAuth = false
                onClearError()
            },
        )
    }
}

@Composable
private fun HighlightRow(highlight: Highlight) {
    Row(
        Modifier.fillMaxWidth().rideCard().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(highlight.icon, background = RideColors.Accent.copy(alpha = 0.12f), size = 42.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(highlight.title, style = MaterialTheme.typography.titleSmall, color = RideColors.TextPrimary)
            Text(highlight.detail, style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
        }
    }
}

@Composable
private fun RoleCard(role: AppRole, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(RideDimens.CardRadius)
    val border by animateColorAsState(if (isSelected) RideColors.Accent else RideColors.Hairline, label = "role-border")
    val borderWidth by animateDpAsState(if (isSelected) 2.dp else 1.dp, label = "role-border-width")
    val glow by animateDpAsState(if (isSelected) 14.dp else 0.dp, spring(dampingRatio = 0.75f), label = "role-glow")
    Column(
        modifier
            .shadow(glow, shape, ambientColor = RideColors.Accent, spotColor = RideColors.Accent)
            .clip(shape)
            .background(RideColors.Surface)
            .border(borderWidth, border, shape)
            .semantics { selected = isSelected }
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(12.dp)).background(RideColors.CanvasDeep.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            if (role == AppRole.RIDER) {
                Canvas(Modifier.size(78.dp, 46.dp).offset(x = (-6).dp, y = 6.dp)) {
                    drawPath(
                        roadCurve(size),
                        RideColors.Accent.copy(alpha = 0.7f),
                        style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
                    )
                    drawCircle(RideColors.TextSecondary, radius = 5.dp.toPx(), center = Offset(4.dp.toPx(), size.height), style = Stroke(2.dp.toPx()))
                }
                Icon(Icons.Rounded.Place, null, tint = RideColors.Accent, modifier = Modifier.size(28.dp).offset(x = 30.dp, y = (-18).dp))
            } else {
                Icon(Icons.Rounded.Speed, null, tint = RideColors.TextSecondary, modifier = Modifier.size(38.dp).offset(x = (-18).dp))
                Icon(Icons.Rounded.DirectionsCar, null, tint = RideColors.Accent, modifier = Modifier.size(30.dp).offset(x = 22.dp, y = 8.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (role == AppRole.RIDER) "I need a ride" else "I want to drive", style = MaterialTheme.typography.titleMedium, color = RideColors.TextPrimary)
            Text(if (role == AppRole.RIDER) "Book, track and pay" else "Earn with RideAX", style = MaterialTheme.typography.bodySmall, color = RideColors.TextSecondary)
        }
    }
}

/**
 * Sign-in sheet. Email + password first, with Apple / Google as additional
 * options; the onboarding role is carried through to the right shell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthSheet(
    role: AppRole,
    state: SessionUiState,
    onAuthenticate: (email: String, password: String, fullName: String?, isSignUp: Boolean) -> Unit,
    onSignIn: (AuthProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    val busy by rememberUpdatedState(state.isAuthenticating)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy })
    var isSignUp by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var fullName by rememberSaveable { mutableStateOf("") }
    var showsPassword by rememberSaveable { mutableStateOf(false) }

    val emailReady = android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    val formReady = emailReady && password.length >= 8 && (!isSignUp || fullName.isNotBlank())
    val submitting = state.isAuthenticating && state.activeProvider == null

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = RideColors.Canvas,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = RideColors.TextSecondary.copy(alpha = 0.4f)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RideDimens.Margin)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark(markSize = 60.dp)
            Spacer(Modifier.height(10.dp))
            Text(if (role == AppRole.RIDER) "Ride in seconds" else "Start earning", style = MaterialTheme.typography.headlineSmall, color = RideColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                if (role == AppRole.RIDER) "Sign in to book rides, follow your driver live and pay securely."
                else "Sign in to set up your driver profile and go online.",
                style = MaterialTheme.typography.bodyMedium,
                color = RideColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(28.dp))

            Spacer(Modifier.height(24.dp))

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(RideColors.CanvasDeep.copy(alpha = 0.5f)).padding(4.dp),
            ) {
                AuthModeButton("Sign in", !isSignUp, Modifier.weight(1f)) {
                    isSignUp = false
                    Haptics.tap()
                }
                AuthModeButton("Create account", isSignUp, Modifier.weight(1f)) {
                    isSignUp = true
                    Haptics.tap()
                }
            }
            Spacer(Modifier.height(16.dp))

            if (isSignUp) {
                AuthField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    label = "Full name",
                    icon = Icons.Rounded.Person,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                Spacer(Modifier.height(12.dp))
            }
            AuthField(
                value = email,
                onValueChange = { email = it },
                label = "Email address",
                icon = Icons.Rounded.Mail,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))
            AuthField(
                value = password,
                onValueChange = { password = it },
                label = if (isSignUp) "Password (8+ characters)" else "Password",
                icon = Icons.Rounded.Lock,
                isPassword = true,
                showsPassword = showsPassword,
                onToggleVisibility = { showsPassword = !showsPassword },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            )
            Spacer(Modifier.height(16.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(RideDimens.ButtonHeight)
                    .clip(RoundedCornerShape(27.dp))
                    .background(if (formReady && !state.isAuthenticating) RideColors.Accent else RideColors.Accent.copy(alpha = 0.45f))
                    .clickable(enabled = formReady && !state.isAuthenticating, role = Role.Button) {
                        Haptics.tap()
                        onAuthenticate(email.trim(), password, fullName.ifBlank { null }, isSignUp)
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (submitting) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = RideColors.OnAccent, strokeWidth = 2.5.dp)
                } else {
                    Text(
                        if (isSignUp) "Create account" else "Sign in",
                        style = MaterialTheme.typography.titleMedium,
                        color = RideColors.OnAccent,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.weight(1f).height(1.dp).background(RideColors.Hairline))
                Text("or continue with", style = MaterialTheme.typography.labelSmall, color = RideColors.TextSecondary)
                Box(Modifier.weight(1f).height(1.dp).background(RideColors.Hairline))
            }

            AuthProvider.entries.forEach { provider ->
                ProviderButton(
                    provider = provider,
                    isLoading = state.isAuthenticating && state.activeProvider == provider,
                    isDimmed = state.isAuthenticating && state.activeProvider != provider,
                    enabled = !state.isAuthenticating,
                    onClick = { onSignIn(provider) },
                )
                Spacer(Modifier.height(12.dp))
            }

            AnimatedVisibility(visible = state.authError != null, enter = fadeIn() + slideInVertically(), exit = fadeOut()) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Warning, null, tint = RideColors.Danger, modifier = Modifier.size(16.dp))
                    Text(state.authError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = RideColors.Danger)
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TrustItem(Icons.Rounded.Lock, "Encrypted")
                TrustItem(Icons.Rounded.CreditCard, "Stripe payments")
                TrustItem(Icons.Rounded.VerifiedUser, "You're in control")
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "By continuing you agree to the RideAX Terms and Privacy Policy.",
                style = MaterialTheme.typography.labelSmall,
                color = RideColors.TextSecondary.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AuthModeButton(label: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (isSelected) RideColors.SurfaceRaised else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (isSelected) RideColors.TextPrimary else RideColors.TextSecondary,
        )
    }
}

@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    keyboardOptions: KeyboardOptions,
    isPassword: Boolean = false,
    showsPassword: Boolean = false,
    onToggleVisibility: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, tint = RideColors.TextSecondary, modifier = Modifier.size(19.dp)) },
        trailingIcon = {
            if (isPassword) {
                val shown = showsPassword
                IconButton(onClick = { onToggleVisibility?.invoke() }) {
                    Icon(
                        if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        if (shown) "Hide password" else "Show password",
                        tint = RideColors.TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        visualTransformation = if (isPassword && !showsPassword) PasswordVisualTransformation() else VisualTransformation.None,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = RideColors.Surface,
            unfocusedContainerColor = RideColors.Surface,
            focusedTextColor = RideColors.TextPrimary,
            unfocusedTextColor = RideColors.TextPrimary,
            focusedBorderColor = RideColors.Accent,
            unfocusedBorderColor = RideColors.Hairline,
            focusedLabelColor = RideColors.Accent,
            unfocusedLabelColor = RideColors.TextSecondary,
            cursorColor = RideColors.Accent,
        ),
    )
}

@Composable
private fun ProviderButton(provider: AuthProvider, isLoading: Boolean, isDimmed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val isApple = provider == AuthProvider.APPLE
    val shape = RoundedCornerShape(27.dp)
    val content = if (isApple) RideColors.OnAccent else RideColors.TextPrimary
    Box(
        Modifier
            .fillMaxWidth()
            .height(RideDimens.ButtonHeight)
            .clip(shape)
            .background(if (isApple) Color.White else RideColors.SurfaceRaised)
            .then(if (isApple) Modifier else Modifier.border(1.dp, RideColors.Hairline, shape))
            .clickable(enabled = enabled, role = Role.Button) {
                Haptics.tap()
                onClick()
            }
            .then(if (isDimmed) Modifier.background(RideColors.Canvas.copy(alpha = 0.5f)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(Modifier.size(22.dp), color = content, strokeWidth = 2.5.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    if (isApple) BrandIcons.Apple else BrandIcons.Google,
                    contentDescription = null,
                    tint = if (isApple) content else Color.Unspecified,
                    modifier = Modifier.size(19.dp),
                )
                Text(provider.title, style = MaterialTheme.typography.titleMedium, color = content)
            }
        }
    }
}

@Composable
private fun TrustItem(icon: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, tint = RideColors.Accent, modifier = Modifier.size(15.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = RideColors.TextSecondary)
    }
}

/** Shown while a stored session is being restored on launch. */
@Composable
fun SplashScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BrandBackdrop()
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BrandMark(markSize = 88.dp)
            BrandWordmark(fontSize = 40.sp)
            Spacer(Modifier.height(10.dp))
            CircularProgressIndicator(Modifier.size(22.dp), color = RideColors.Accent, strokeWidth = 2.5.dp)
        }
    }
}
