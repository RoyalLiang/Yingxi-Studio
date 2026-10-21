"""APK names and native architecture checks shared by build and release tools."""
import zipfile

ABIS=("arm64-v8a","armeabi-v7a","x86_64","x86")
def apk_name(version,abi,build_type=None):
    if abi not in ABIS:raise ValueError("Unsupported APK architecture")
    suffix="" if abi=="arm64-v8a" else "-"+abi
    kind="-"+build_type if build_type else ""
    return f'yingxi-{version["versionName"]}-{version["versionCode"]}{suffix}{kind}.apk'

def native_abis(path):
    with zipfile.ZipFile(path) as archive:
        return {n.split("/")[1] for n in archive.namelist() if n.startswith("lib/") and n.endswith(".so") and len(n.split("/"))==3}

def verify_abi(path,abi):
    if abi not in ABIS or native_abis(path)!={abi}:raise ValueError("APK must contain exactly its declared architecture: "+abi)

def outputs(metadata,directory,version):
    found={}
    for element in metadata["elements"]:
        filters=element.get("filters",[])
        if len(filters)!=1 or filters[0].get("filterType")!="ABI":raise ValueError("Universal or unrecognized APK output")
        abi=filters[0].get("value")
        if abi not in ABIS or abi in found:raise ValueError("Duplicate or unsupported APK output")
        path=(directory/element["outputFile"]).resolve()
        if path.parent!=directory.resolve() or path.suffix!=".apk":raise ValueError("Unsafe APK output path")
        if element.get("versionCode")!=version["versionCode"] or element.get("versionName")!=version["versionName"]:raise ValueError("APK output version mismatch")
        verify_abi(path,abi);found[abi]=path
    if set(found)!=set(ABIS):raise ValueError("Missing architecture APK output")
    return found
