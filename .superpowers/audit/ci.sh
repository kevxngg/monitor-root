#!/bin/bash
# Pushes HEAD to the audit branch, waits for the Audit workflow of that commit, prints jobs and failures.
cd "$(git rev-parse --show-toplevel)"
SHA=$(git rev-parse HEAD); B=$(git branch --show-current)
git push -q origin HEAD </dev/null || exit 2
for i in $(seq 1 40); do ID=$(gh run list --branch "$B" --workflow Audit --limit 5 --json databaseId,headSha --jq ".[] | select(.headSha==\"$SHA\") | .databaseId" </dev/null | head -1); [ -n "$ID" ] && break; sleep 3; done
[ -z "$ID" ] && { echo "no run"; exit 2; }
gh run watch "$ID" --interval 15 </dev/null > /dev/null 2>&1
echo "run $ID ($(echo $SHA | cut -c1-7))"
gh run view "$ID" --json jobs --jq '.jobs[] | "\(.name): \(.conclusion)"' </dev/null
gh run view "$ID" --log-failed </dev/null 2>/dev/null | grep -E "e: file|FAILED|tests completed" | sed 's/.*\/monitorroot\///; s/^[^\t]*\t[^\t]*\t[0-9TZ:.-]* //' | cut -c1-120 | sort -u | head -25
gh run view "$ID" --json conclusion --jq .conclusion </dev/null | grep -q success
