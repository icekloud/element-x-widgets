"""Element June: turn the GOOGLE_SERVICES_JSON secret into Firebase string resources at build time.

Nothing is printed; the generated files only live in the CI checkout and are never committed.
"""
import json
import os
from xml.sax.saxutils import escape

PACKAGE = "io.element.android.june"
BASE = "libraries/pushproviders/firebase/src"

data = json.loads(os.environ["GOOGLE_SERVICES_JSON"])
project = data["project_info"]
client = next(c for c in data["client"] if c["client_info"]["android_client_info"]["package_name"] == PACKAGE)
api_key = client["api_key"][0]["current_key"]


def res(values: dict) -> str:
    rows = "\n".join(
        f'    <string name="{k}" translatable="false" tools:ignore="UnusedResources">{escape(v)}</string>' for k, v in values.items()
    )
    return f'<?xml version="1.0" encoding="utf-8"?>\n<resources xmlns:tools="http://schemas.android.com/tools">\n{rows}\n</resources>\n'


main = {
    "gcm_defaultSenderId": project["project_number"],
    "google_api_key": api_key,
    "google_crash_reporting_api_key": api_key,
    "google_storage_bucket": project.get("storage_bucket", ""),
    "project_id": project["project_id"],
}
with open(f"{BASE}/main/res/values/firebase.xml", "w") as f:
    f.write(res(main))
with open(f"{BASE}/release/res/values/firebase.xml", "w") as f:
    f.write(res({"google_app_id": client["client_info"]["mobilesdk_app_id"]}))
print("Firebase config written")
