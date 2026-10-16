package cn.ianzb.hyperrefine.hook.packageinstaller

/** Static, bounded diagnostics. Never invokes installer methods or reads pending APK information. */
internal object InstallerDiscoveryProbe {
    fun collect(index: InstallerDexIndex): List<String> {
        val lines = mutableListOf<String>()
        fun probe(name: String, block: () -> String) {
            val detail = runCatching(block).getOrElse { "unavailable=${it.javaClass.simpleName}" }
            lines += "probe $name detail=$detail".take(384)
        }
        probe("layout_maps") {
            val maps = index.findMethods(InstallerMethodQuery(strings = setOf("packageName", "installSource",
                "newInstall", "appName", "isPersonalizedAdEnabled"))).distinctBy { it.descriptor }
            "count=${maps.size}; " + maps.take(2).joinToString("; ") {
                "${it.descriptor} invokes=${it.invokes.size}/${it.invokes.distinct().size} reads=${it.readFields.size}/${it.readFields.distinct().size}"
            }
        }
        val mapApis = linkedMapOf<String, InstallerMethod>()
        for (type in listOf("java.util.Map", "java.util.HashMap")) {
            for (params in listOf(listOf(type), listOf(type, null))) {
                runCatching { index.findMethods(InstallerMethodQuery(parameterTypes = params)) }
                    .getOrDefault(emptyList()).forEach { mapApis[it.descriptor] = it }
            }
        }
        probe("map_api_annotations") {
            val annotated = mapApis.values.filter { it.annotationStrings.isNotEmpty() }
            "map_first=${mapApis.size}; annotated=${annotated.size}; layout_named=${annotated.count { method -> method.annotationStrings.any { it.contains("layout", true) } }}"
        }
        mapApis.values.filter { method -> method.annotationStrings.any { it.contains("layout", true) } }
            .take(2).forEachIndexed { ordinal, method ->
                probe("layout_api_$ordinal") {
                    // Only fixed path-like layout annotation constants, never URL queries/hosts.
                    val paths = method.annotationStrings.filter { it.contains("layout", true) }.mapNotNull {
                        runCatching { java.net.URI(it).rawPath }.getOrNull()
                            ?.takeIf { path -> path.length <= 96 && path.matches(Regex("[A-Za-z0-9_./-]+")) }
                    }.distinct().take(2)
                    "${method.descriptor}; paths=${paths.joinToString()}; annotations=${method.annotationStrings.size}"
                }
            }
        return lines
    }
}
