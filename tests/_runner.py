import os
import sys
import unittest

root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(root)
sys.path.insert(0, root)

argv = sys.argv[1:] or ['tests.test_settings']
unittest.main(module=None, argv=['runner', '-v'] + argv, exit=True)
