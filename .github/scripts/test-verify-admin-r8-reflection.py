#!/usr/bin/env python3
"""Self-test the Admin R8 reflective-entrypoint mapping verifier."""

import importlib.util
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("verify-admin-r8-reflection.py")
SPEC = importlib.util.spec_from_file_location("admin_r8_verifier", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


def mapping_for(class_name: str, target_name: str | None = None, constructor: bool = True) -> str:
    target = target_name or class_name
    member = "    public void <init>() -> <init>\n" if constructor else ""
    return f"{class_name} -> {target}:\n{member}example.Type -> example.Type:\n"


class VerifyAdminR8ReflectionTest(unittest.TestCase):
    def test_accepts_preserved_coroutine_service_providers(self):
        content = "\n".join(mapping_for(name) for name in MODULE.REQUIRED)
        MODULE.verify_mapping(content)

    def test_rejects_renamed_dispatcher_factory(self):
        content = mapping_for(MODULE.REQUIRED[0], "a.b") + mapping_for(MODULE.REQUIRED[1])
        with self.assertRaisesRegex(ValueError, "renamed or removed"):
            MODULE.verify_mapping(content)

    def test_rejects_removed_zero_argument_constructor(self):
        content = mapping_for(MODULE.REQUIRED[0], constructor=False) + mapping_for(MODULE.REQUIRED[1])
        with self.assertRaisesRegex(ValueError, "zero-argument constructor"):
            MODULE.verify_mapping(content)

    def test_rejects_missing_provider(self):
        with self.assertRaisesRegex(ValueError, "renamed or removed"):
            MODULE.verify_mapping(mapping_for(MODULE.REQUIRED[1]))


if __name__ == "__main__":
    unittest.main()
