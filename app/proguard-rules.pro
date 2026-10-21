# Scrollhalt release shrinking rules.
#
# Nothing extra is needed today: the accessibility service, activity and the
# accessibility config are referenced from the manifest, which AGP keeps
# automatically, and the app uses no reflection. DataStore, coroutines and
# Compose ship their own consumer rules.
#
# Add -keep rules here if a future change loads classes by name.
