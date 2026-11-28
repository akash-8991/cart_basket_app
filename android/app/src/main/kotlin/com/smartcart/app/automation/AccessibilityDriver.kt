package com.smartcart.app.automation

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.smartcart.automation.Driver
import com.smartcart.automation.PackageVerifier
import com.smartcart.automation.ScreenSnapshot
import com.smartcart.automation.UiNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Phase 4.1: the [Driver] over a bound AccessibilityService. Reads only semantic node properties, never the
 * text of password fields, and acts only with node actions (click, set text) and the global Back — no
 * gestures or coordinates. Nothing it reads is logged.
 */
class AccessibilityDriver(private val service: AccessibilityService) : Driver {
    override suspend fun launch(packageName: String): Boolean = withContext(Dispatchers.Main) {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName) ?: return@withContext false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { service.startActivity(intent) }.isSuccess
    }

    override fun snapshot(): ScreenSnapshot? {
        val root = service.rootInActiveWindow
        if (root != null) return root.packageName?.toString()?.let { ScreenSnapshot(it, convert(root, 0)) }
        // The service only sees its allowlisted apps' windows. An active window it can't see means some
        // other app is in front: report that, so the runner halts with a package mismatch.
        val active = runCatching { service.windows }.getOrNull()?.firstOrNull { it.isActive } ?: return null
        return ScreenSnapshot(active.root?.packageName?.toString() ?: HIDDEN, UiNode())
    }

    private fun convert(n: AccessibilityNodeInfo, depth: Int): UiNode = UiNode(
        id = n.viewIdResourceName,
        text = if (n.isPassword) null else n.text?.toString(),
        desc = n.contentDescription?.toString(),
        hint = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString() else null,
        className = n.className?.toString(),
        clickable = n.isClickable,
        editable = n.isEditable,
        password = n.isPassword,
        children = if (depth > 40) emptyList() else (0 until n.childCount).mapNotNull { i -> n.getChild(i)?.let { convert(it, depth + 1) } },
        handle = n,
    )

    override fun click(node: UiNode): Boolean {
        var n = node.handle as? AccessibilityNodeInfo ?: return false
        while (!n.isClickable) n = n.parent ?: return false
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun setText(node: UiNode, text: String): Boolean {
        val n = node.handle as? AccessibilityNodeInfo ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun back(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    companion object { const val HIDDEN = "(not visible to SmartCart)" }
}

/** Installed packages and their current signing certificates, for the adapter allowlist (threat model A-09). */
class AndroidPackageVerifier(private val context: Context) : PackageVerifier {
    private val pm get() = context.packageManager

    override fun isInstalled(packageName: String) = runCatching { pm.getPackageInfo(packageName, 0) }.isSuccess

    @Suppress("DEPRECATION")
    override fun signingDigests(packageName: String): Set<String> = runCatching {
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners.orEmpty()
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        }
        certs.map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }.getOrDefault(emptySet())
}
