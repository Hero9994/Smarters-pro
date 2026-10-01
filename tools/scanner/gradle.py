"""Invoke local Gradle using this execution's proxy endpoint and system CA trust."""
import os
from pathlib import Path
import sys
import urllib.parse
import urllib.request

root=Path(__file__).resolve().parents[3]/"toolchain"
proxy=urllib.parse.urlparse(urllib.request.getproxies().get("https",""))
home=root/"gradle-user-home"; home.mkdir(exist_ok=True)
properties=["org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts","org.gradle.workers.max=4"]
if proxy.hostname:
    for protocol in ("http","https"):
        properties += [f"systemProp.{protocol}.proxyHost={proxy.hostname}",f"systemProp.{protocol}.proxyPort={proxy.port}"]
    properties += ["systemProp.http.nonProxyHosts=localhost|127.*"]
(home/"gradle.properties").write_text("\n".join(properties)+"\n")
os.environ.update(JAVA_HOME=str(root/"jdk"),ANDROID_HOME=str(root/"android-sdk"),GRADLE_USER_HOME=str(home),JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts")
os.environ["PATH"]=str(root/"jdk/bin")+":"+os.environ["PATH"]
os.execv(str(root/"gradle-9.5.0/bin/gradle"),["gradle",*sys.argv[1:],"--console=plain"])
