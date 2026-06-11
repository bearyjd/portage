# Shizuku spawns the UserService in a separate shell-uid process and instantiates it
# reflectively by class name (via UserServiceArgs/ComponentName), then talks to it over the
# AIDL stub. R8 must not rename or strip the class, its constructors, or the binder contract.
-keep class cc.grepon.portage.privileged.PrivilegedService { *; }
-keep class cc.grepon.portage.privileged.IPrivilegedService { *; }
-keep class cc.grepon.portage.privileged.IPrivilegedService$* { *; }
