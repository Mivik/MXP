package com.mivik.mxp

import de.robv.android.xposed.IXposedHookInitPackageResources
import de.robv.android.xposed.callbacks.XC_InitPackageResources

class MXPHookInitPackageResources : IXposedHookInitPackageResources {
	override fun handleInitPackageResources(resparam: XC_InitPackageResources.InitPackageResourcesParam?) {
		val module = MXPModule.getInstance(MXP.apkFile) ?: return
		module.ensureLoaded()
		module.handleInitPackageResources(resparam)
	}
}