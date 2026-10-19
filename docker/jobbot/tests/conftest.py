import sys
from pathlib import Path

JOBBOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(JOBBOT / "plugins"))
sys.path.insert(0, str(JOBBOT))
