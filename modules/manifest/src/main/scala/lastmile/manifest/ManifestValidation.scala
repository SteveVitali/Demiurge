package lastmile.manifest

// Spec §5: Manifest validation for Phase 3.
// Validates parsed LastmileManifest for structural correctness.
object ManifestValidation {

  case class ValidationResult(errors: List[String], warnings: List[String]) {
    def isValid: Boolean = errors.isEmpty
  }

  def validate(manifest: LastmileManifest): ValidationResult = {
    val errors = scala.collection.mutable.ListBuffer[String]()
    val warnings = scala.collection.mutable.ListBuffer[String]()

    // version must be 1
    if (manifest.version != 1)
      errors += s"version must be 1, got: ${manifest.version}"

    // at least one service must be defined
    if (manifest.services.isEmpty)
      errors += "at least one service must be defined"

    // service dependency references must point to existing services
    manifest.services.foreach { case (name, svc) =>
      svc.dependsOn.foreach { deps =>
        deps.foreach { dep =>
          if (!manifest.services.contains(dep))
            errors += s"services.$name.depends_on references unknown service '$dep'"
        }
      }
    }

    // startup_mode=script requires startup_command
    manifest.services.foreach { case (name, svc) =>
      if (svc.startupMode.toLowerCase == "script" && svc.startupCommand.isEmpty)
        errors += s"services.$name: startup_mode=script requires startup_command"
      if (svc.startupMode.toLowerCase == "compose" && svc.composeTarget.isEmpty)
        errors += s"services.$name: startup_mode=compose requires compose_target"
    }

    // must have ports or readiness
    manifest.services.foreach { case (name, svc) =>
      if (svc.ports.isEmpty && svc.readiness.isEmpty)
        errors += s"services.$name: at least one of ports or readiness must be specified"
    }

    // fixture seed_steps depends_on_services must reference existing services
    manifest.fixtures.foreach { fix =>
      fix.seedSteps.foreach { steps =>
        steps.foreach { step =>
          step.dependsOnServices.foreach { deps =>
            deps.foreach { dep =>
              if (!manifest.services.contains(dep))
                errors += s"fixtures.seed_steps[${step.stepId}].depends_on_services references unknown service '$dep'"
            }
          }
        }
      }
    }

    ValidationResult(errors.toList, warnings.toList)
  }
}
