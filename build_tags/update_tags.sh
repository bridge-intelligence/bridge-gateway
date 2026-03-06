#!/bin/bash

# Script to automatically increment semantic version tags
# Usage: ./update_tags.sh -v [major|minor|patch]

# Function to compare semantic version numbers
function version_greater() {
    printf "%s\n%s\n" "$1" "$2" | sort -V | tail -n 1
}

VERSION=""

# Get parameters
while getopts v: flag
do
    case "${flag}" in
        v) VERSION=${OPTARG};;
    esac
done

echo "Fetching all tags from the remote repository..."
git fetch --tags

# Get the latest tag
LATEST_TAG=$(git for-each-ref refs/tags --sort=-version:refname --format='%(refname:short)' --count=1)

# If there are no existing tags, set a default starting version
if [[ -z $LATEST_TAG ]]
then
    LATEST_TAG='v0.0.0'
fi

echo "Starting Version: $LATEST_TAG"

# Remove 'v' prefix and split version into parts
LATEST_TAG=${LATEST_TAG//v/}
LATEST_TAG_PARTS=(${LATEST_TAG//./ })

# Get number part of versioning
VNUM1=${LATEST_TAG_PARTS[0]}
VNUM2=${LATEST_TAG_PARTS[1]}
VNUM3=${LATEST_TAG_PARTS[2]}

echo "Current Version parts: $VNUM1.$VNUM2.$VNUM3"

# Increment version based on the specified type (major, minor, patch)
if [[ $VERSION == 'major' ]]
then
    VNUM1=$((VNUM1+1))
    VNUM2=0
    VNUM3=0
elif [[ $VERSION == 'minor' ]]
then
    VNUM2=$((VNUM2+1))
    VNUM3=0
elif [[ $VERSION == 'patch' ]]
then
    # Patch version increment only
    VNUM3=$((VNUM3+1))
else
    echo "No version type (https://semver.org/) or incorrect type specified, try -v [major, minor, patch]"
    exit 1
fi

NEW_TAG="v$VNUM1.$VNUM2.$VNUM3"
echo "($VERSION) updating $LATEST_TAG to $NEW_TAG"

# Check if the new tag already exists in the remote
if git ls-remote --tags | grep -q "$NEW_TAG"
then
    echo "Tag $NEW_TAG already exists in remote repository"
    exit 1
fi

# Get current hash and see if it already has a tag
GIT_COMMIT=$(git rev-parse HEAD)
NEEDS_TAG=$(git describe --contains $GIT_COMMIT 2>/dev/null)

# Only tag if no tag already
if [[ -z $NEEDS_TAG ]]; then
    echo "Tagging with $NEW_TAG"
    git tag $NEW_TAG
    git push --tags
else
    echo "Already a tag on this commit"
fi

# Output for GitHub Actions (using new syntax)
if [ -n "$GITHUB_OUTPUT" ]; then
    echo "git-tag=$NEW_TAG" >> $GITHUB_OUTPUT
fi
# Also output to stdout for compatibility and to capture in workflow
echo "NEW_TAG=$NEW_TAG"
echo "::set-output name=git-tag::$NEW_TAG"  # Legacy format for compatibility
