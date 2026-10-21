# Hilt, Room and Compose ship consumer rules. Only keep what the manifest cannot express.
-keep class com.flowstate.focus.service.FocusBlockerService { *; }
-keep class com.flowstate.focus.service.TimerService { *; }
