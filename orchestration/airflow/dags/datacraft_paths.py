"""Filesystem boundaries for worker-local downloads, without importing Airflow.

The data directory and its parents must be controlled by the worker administrator. These checks
reject existing links outside the root; a provider that opens a pathname later cannot prevent a
hostile local process from replacing directories between validation and the open.
"""

from pathlib import Path


def download_destination(raw: str, data_root: str) -> Path:
    """Return the resolved destination beneath an existing, trusted data directory.

    Missing destination directories are allowed: the SFTP provider creates them only after the
    guard succeeds. Both existing parent links and an existing destination link are resolved.
    """
    destination = Path(raw)
    root = Path(data_root)
    if not destination.is_absolute() or not destination.is_relative_to(root) or destination == root:
        raise ValueError("Download destination must be a path beneath DATACRAFT_DATA_ROOT")
    try:
        real_root = root.resolve(strict=True)
        if not real_root.is_dir():
            raise ValueError("DATACRAFT_DATA_ROOT must be an existing directory")
        resolved = destination.resolve(strict=False)
    except (OSError, RuntimeError) as error:
        raise ValueError("Download destination or DATACRAFT_DATA_ROOT cannot be resolved") from error
    if resolved == real_root or not resolved.is_relative_to(real_root):
        raise ValueError("Download destination resolves outside DATACRAFT_DATA_ROOT")
    return resolved
