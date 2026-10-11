"""Where the lab keeps its data on the lab PC (everything stays inside $LAB)."""
import os
from pathlib import Path

LAB = Path(os.environ.get("LAB", Path.home() / "AI_Lab-FaceTraining"))
MODELS = LAB / "models"
DATA = LAB / "data"
RESULTS = LAB / "results"
