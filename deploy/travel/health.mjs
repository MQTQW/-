// No bearer is needed to verify that the private API is alive and rejecting unauthenticated access.
try {
  const response=await fetch('http://127.0.0.1:8794/api/native/session',{signal:AbortSignal.timeout(3000)});
  const data=await response.json();
  process.exitCode=response.status===401&&data.error?.code==='NATIVE_AUTH_REQUIRED'?0:1;
} catch { process.exitCode=1; }

