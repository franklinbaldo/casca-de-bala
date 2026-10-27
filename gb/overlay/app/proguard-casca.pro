# Casca flavor: the MCP bridge is called from JNI by name (McpNative.control)
# and its components are referenced from the flavor manifest. Keep them intact.
-keep class org.workspace.casca.** { *; }
