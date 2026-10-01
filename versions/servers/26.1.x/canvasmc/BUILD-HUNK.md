# Canvas build wiring, line 26.1

Historical note kept for provenance.

The compression library coordinate used to be listed here.
It now lives in the stage overlay automation and is applied to the generated server build file after upstream apply, since that file does not exist before apply.
Test sources need no build file edit because the generated build already consumes the test tree.

Same library choice as the Folia leg.
