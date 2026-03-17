# TEMPLATE-INCLUDE-BEGIN
load("@rules_scala//scala:providers.bzl", "ScalaInfo")
# TEMPLATE-INCLUDE-END

def scala_info_in_target(target):
    """Returns True if the target has ScalaInfo provider, indicating it's a Scala target."""

# TEMPLATE-INCLUDE-BEGIN
    return ScalaInfo in target
# TEMPLATE-INCLUDE-END
