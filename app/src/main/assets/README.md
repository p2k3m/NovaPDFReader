# Assets Directory

This directory intentionally does not store binary fixtures. Instrumentation tests fetch the CC0 1.0 sample PDF from S3 at
runtime (see the repository README for configuration details) and place it in the device cache instead.

Feel free to drop additional local test documents here while iterating, but do not commit them to the repository.
