package com.mivik.mxp

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MXPHookLoadPackage : IXposedHookLoadPackage {
	override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
		val module = MXPModule.getInstance(MXP.apkFile) ?: return
		module.ensureLoaded()
		module.handleLoadPackage(lpparam)
	}
}