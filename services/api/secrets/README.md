# Directory: services/api/secrets/

Place your private service account keys and certificate files in this directory.

### Firebase Service Account Setup
1. Go to [Firebase Console](https://console.firebase.google.com/) -> **Project Settings** -> **Service accounts**.
2. Click **Generate new private key**.
3. Save the downloaded JSON file here as:
   `services/api/secrets/firebase-service-account.json`

> **Security Warning:**
> Never commit JSON keys, private keys, or credentials to Git. This folder is ignored by `.gitignore` (except this README).
