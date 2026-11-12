#include <sys/attr.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/vnode.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <uuid/uuid.h>

static int valid_utf8(const unsigned char *value, size_t length) {
    size_t i = 0;
    while (i < length) {
        unsigned char first = value[i++];
        if (first < 0x80) continue;
        unsigned count;
        uint32_t point;
        if (first >= 0xc2 && first <= 0xdf) { count = 1; point = first & 0x1f; }
        else if (first >= 0xe0 && first <= 0xef) { count = 2; point = first & 0x0f; }
        else if (first >= 0xf0 && first <= 0xf4) { count = 3; point = first & 0x07; }
        else return 0;
        if (length - i < count) return 0;
        for (unsigned j = 0; j < count; j++) {
            unsigned char next = value[i++];
            if ((next & 0xc0) != 0x80) return 0;
            point = (point << 6) | (next & 0x3f);
        }
        if ((count == 2 && point < 0x800) || (count == 3 && point < 0x10000) ||
            (point >= 0xd800 && point <= 0xdfff) || point > 0x10ffff) return 0;
    }
    return 1;
}

/* 获取同一目录描述符的卷身份；调用方负责运行期间的挂载生命周期。 */
static int json_string(const char *value, size_t capacity) {
    size_t length = strnlen(value, capacity);
    if (length == 0 || length == capacity || !valid_utf8((const unsigned char *)value, length)) return -1;
    if (putchar('"') == EOF) return -1;
    for (size_t i = 0; i < length; i++) {
        unsigned char ch = (unsigned char)value[i];
        if (ch == '"' || ch == '\\') {
            if (putchar('\\') == EOF || putchar(ch) == EOF) return -1;
        } else if (ch < 0x20) {
            if (printf("\\u%04x", ch) < 0) return -1;
        } else if (putchar(ch) == EOF) return -1;
    }
    return putchar('"') == EOF ? -1 : 0;
}

struct identity {
    struct stat st;
    struct statfs fs;
    uuid_t uuid;
};

static int read_identity(int fd, struct identity *result) {
    struct attrlist attrs = {0};
    struct { uint32_t length; uuid_t uuid; } volume = {0};
    attrs.bitmapcount = ATTR_BIT_MAP_COUNT;
    attrs.volattr = ATTR_VOL_INFO | ATTR_VOL_UUID;
    if (fstat(fd, &result->st) != 0 || !S_ISDIR(result->st.st_mode) || fstatfs(fd, &result->fs) != 0 ||
        fgetattrlist(fd, &attrs, &volume, sizeof(volume), 0) != 0 ||
        volume.length != sizeof(volume) || uuid_is_null(volume.uuid) ||
        strnlen(result->fs.f_mntonname, sizeof(result->fs.f_mntonname)) == sizeof(result->fs.f_mntonname) ||
        strnlen(result->fs.f_fstypename, sizeof(result->fs.f_fstypename)) == sizeof(result->fs.f_fstypename)) return -1;
    uuid_copy(result->uuid, volume.uuid);
    return 0;
}

static int same_identity(const struct identity *a, const struct identity *b) {
    return a->st.st_dev == b->st.st_dev && a->st.st_ino == b->st.st_ino &&
        memcmp(&a->fs.f_fsid, &b->fs.f_fsid, sizeof(a->fs.f_fsid)) == 0 &&
        a->fs.f_flags == b->fs.f_flags &&
        uuid_compare(a->uuid, b->uuid) == 0 &&
        strcmp(a->fs.f_mntonname, b->fs.f_mntonname) == 0 &&
        strcmp(a->fs.f_fstypename, b->fs.f_fstypename) == 0;
}

static int print_identity(const struct identity *identity) {
    uuid_string_t uuid;
    uuid_unparse_upper(identity->uuid, uuid);
    if (printf("{\"device\":\"%" PRIuMAX "\",\"inode\":\"%" PRIuMAX
               "\",\"volumeUUID\":\"%s\",\"mountFlags\":%" PRIuMAX
               ",\"ignoreOwnership\":%s,\"fsType\":", (uintmax_t)identity->st.st_dev,
               (uintmax_t)identity->st.st_ino, uuid, (uintmax_t)identity->fs.f_flags,
               (identity->fs.f_flags & MNT_IGNORE_OWNERSHIP) ? "true" : "false") < 0 ||
        json_string(identity->fs.f_fstypename, sizeof(identity->fs.f_fstypename)) != 0 ||
        printf(",\"mountPoint\":") < 0 ||
        json_string(identity->fs.f_mntonname, sizeof(identity->fs.f_mntonname)) != 0 ||
        putchar('}') == EOF) return -1;
    return 0;
}

struct __attribute__((packed, aligned(4))) base_attributes {
    uint32_t length;
    attribute_set_t returned;
    dev_t device;
    fsobj_type_t type;
    uid_t owner;
    uint32_t mode;
    attrreference_t acl;
    uint64_t inode;
    uint32_t entries;
    uint32_t mount_status;
};

static int read_base_attributes(int parent_fd, const char *name, struct base_attributes *result) {
    const attrgroup_t required = ATTR_CMN_RETURNED_ATTRS | ATTR_CMN_DEVID | ATTR_CMN_OBJTYPE | ATTR_CMN_OWNERID |
        ATTR_CMN_ACCESSMASK | ATTR_CMN_FILEID;
    struct attrlist attrs = {0};
    unsigned char buffer[8192] = {0};
    attrs.bitmapcount = ATTR_BIT_MAP_COUNT;
    attrs.commonattr = ATTR_CMN_RETURNED_ATTRS | required | ATTR_CMN_EXTENDED_SECURITY;
    attrs.dirattr = ATTR_DIR_ENTRYCOUNT | ATTR_DIR_MOUNTSTATUS;
    if (getattrlistat(parent_fd, name, &attrs, buffer, sizeof(buffer),
                      FSOPT_NOFOLLOW_ANY | FSOPT_REPORT_FULLSIZE | FSOPT_PACK_INVAL_ATTRS) != 0) return -1;
    memcpy(result, buffer, sizeof(*result));
    if (result->length < sizeof(*result) || result->length > sizeof(buffer) ||
        (result->returned.commonattr & required) != required ||
        (result->returned.dirattr & attrs.dirattr) != attrs.dirattr ||
        (result->returned.commonattr & ATTR_CMN_EXTENDED_SECURITY) != 0 || result->acl.attr_length != 0 ||
        result->type != VDIR || (result->mode & 07777) != 0 || result->entries != 0 ||
        (result->mount_status & DIR_MNTSTATUS_MNTPOINT) != 0) return -1;
    return 0;
}

/* 在不打开或改变 mode 000 目录的情况下核验未挂载的空底座。 */
static int check_base(const char *path) {
    if (!path || path[0] != '/' || strlen(path) >= PATH_MAX) return 2;
    char parent_path[PATH_MAX];
    strcpy(parent_path, path);
    char *slash = strrchr(parent_path, '/');
    if (!slash || slash == parent_path || slash[1] == '\0' || strcmp(slash + 1, ".") == 0 ||
        strcmp(slash + 1, "..") == 0) return 2;
    char *name = slash + 1;
    *slash = '\0';
    int parent_fd = open(parent_path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (parent_fd < 0) return 1;
    struct attrlist volume_attrs = {0};
    struct { uint32_t length; vol_capabilities_attr_t caps; } volume = {0};
    struct stat parent, path_stat;
    struct base_attributes first, last;
    volume_attrs.bitmapcount = ATTR_BIT_MAP_COUNT;
    volume_attrs.volattr = ATTR_VOL_INFO | ATTR_VOL_CAPABILITIES;
    const uint32_t acl_cap = VOL_CAP_INT_EXTENDED_SECURITY;
    if (fstat(parent_fd, &parent) != 0 || !S_ISDIR(parent.st_mode) ||
        fgetattrlist(parent_fd, &volume_attrs, &volume, sizeof(volume), 0) != 0 ||
        volume.length != sizeof(volume) ||
        (volume.caps.valid[VOL_CAPABILITIES_INTERFACES] & acl_cap) == 0 ||
        (volume.caps.capabilities[VOL_CAPABILITIES_INTERFACES] & acl_cap) == 0 ||
        read_base_attributes(parent_fd, name, &first) != 0 ||
        fstatat(parent_fd, name, &path_stat, AT_SYMLINK_NOFOLLOW) != 0 || !S_ISDIR(path_stat.st_mode) ||
        first.device != parent.st_dev || first.device != path_stat.st_dev || first.inode != path_stat.st_ino ||
        first.owner != path_stat.st_uid || (path_stat.st_mode & 07777) != 0 ||
        read_base_attributes(parent_fd, name, &last) != 0 ||
        first.device != last.device || first.inode != last.inode || first.owner != last.owner ||
        first.mode != last.mode || first.entries != last.entries || first.mount_status != last.mount_status) {
        fputs("unable to verify unmounted empty base\n", stderr);
        close(parent_fd); return 1;
    }
    int result = printf("{\"parent\":{\"device\":\"%" PRIuMAX "\",\"inode\":\"%" PRIuMAX
                        "\"},\"base\":{\"device\":\"%" PRIuMAX "\",\"inode\":\"%" PRIuMAX
                        "\",\"uid\":%" PRIuMAX ",\"mode\":0,\"entries\":0,\"hasACL\":false}}\n",
                        (uintmax_t)parent.st_dev, (uintmax_t)parent.st_ino,
                        (uintmax_t)first.device, (uintmax_t)first.inode, (uintmax_t)first.owner);
    if (result < 0 || fflush(stdout) != 0) { close(parent_fd); return 1; }
    return close(parent_fd) == 0 ? 0 : 1;
}

int main(int argc, char **argv) {
    if (argc == 3 && strcmp(argv[1], "check-base") == 0) return check_base(argv[2]);
    if (argc != 2 || argv[1][0] != '/' || strlen(argv[1]) >= PATH_MAX) {
        fputs("absolute project directory required\n", stderr);
        return 2;
    }
    char base_path[PATH_MAX];
    strcpy(base_path, argv[1]);
    char *slash = strrchr(base_path, '/');
    if (!slash || slash == base_path || slash[1] == '\0' || strcmp(slash + 1, ".") == 0 ||
        strcmp(slash + 1, "..") == 0) {
        fputs("project must be a direct child of the volume\n", stderr);
        return 2;
    }
    char *name = slash + 1;
    *slash = '\0';
    int base_fd = open(base_path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (base_fd < 0) { perror("open volume"); return 1; }
    int project_fd = openat(base_fd, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (project_fd < 0) { perror("open project"); close(base_fd); return 1; }
    struct identity base, project, base_final, project_final;
    struct stat base_path_stat, project_path_stat, base_mount_stat, project_mount_stat;
    struct statfs *mounts = NULL;
    if (read_identity(base_fd, &base) != 0 || read_identity(project_fd, &project) != 0 ||
        strcmp(base.fs.f_fstypename, "apfs") != 0 || strcmp(project.fs.f_fstypename, "apfs") != 0 ||
        stat(base.fs.f_mntonname, &base_mount_stat) != 0 || stat(project.fs.f_mntonname, &project_mount_stat) != 0 ||
        !S_ISDIR(base_mount_stat.st_mode) || !S_ISDIR(project_mount_stat.st_mode) ||
        base_mount_stat.st_dev != base.st.st_dev || base_mount_stat.st_ino != base.st.st_ino ||
        project_mount_stat.st_dev != base.st.st_dev || project_mount_stat.st_ino != base.st.st_ino ||
        base.st.st_dev != project.st.st_dev || base.fs.f_flags != project.fs.f_flags ||
        uuid_compare(base.uuid, project.uuid) != 0 ||
        memcmp(&base.fs.f_fsid, &project.fs.f_fsid, sizeof(base.fs.f_fsid)) != 0) {
        fputs("unable to identify dedicated APFS project volume\n", stderr);
        close(project_fd); close(base_fd); return 1;
    }
    int count = getmntinfo(&mounts, MNT_NOWAIT);
    if (count <= 0 || mounts == NULL || read_identity(base_fd, &base_final) != 0 ||
        read_identity(project_fd, &project_final) != 0 || !same_identity(&base, &base_final) ||
        !same_identity(&project, &project_final) || lstat(base_path, &base_path_stat) != 0 ||
        fstatat(base_fd, name, &project_path_stat, AT_SYMLINK_NOFOLLOW) != 0 ||
        !S_ISDIR(base_path_stat.st_mode) || !S_ISDIR(project_path_stat.st_mode) ||
        base_path_stat.st_dev != base.st.st_dev || base_path_stat.st_ino != base.st.st_ino ||
        project_path_stat.st_dev != project.st.st_dev || project_path_stat.st_ino != project.st.st_ino) {
        fputs("unable to read stable mount information\n", stderr);
        close(project_fd); close(base_fd); return 1;
    }
    for (int i = 0; i < count; i++) {
        size_t length = strnlen(mounts[i].f_mntonname, sizeof(mounts[i].f_mntonname));
        if (length == 0 || length == sizeof(mounts[i].f_mntonname) || mounts[i].f_mntonname[0] != '/' ||
            !valid_utf8((const unsigned char *)mounts[i].f_mntonname, length)) {
            fputs("invalid mount point\n", stderr);
            close(project_fd); close(base_fd); return 1;
        }
    }
    if (printf("{\"base\":") < 0 || print_identity(&base) != 0 || printf(",\"root\":") < 0 ||
        print_identity(&project) != 0 || printf(",\"mounts\":[") < 0) {
        close(project_fd); close(base_fd); return 1;
    }
    for (int i = 0; i < count; i++) {
        if ((i && putchar(',') == EOF) || json_string(mounts[i].f_mntonname, sizeof(mounts[i].f_mntonname)) != 0) {
            close(project_fd); close(base_fd); return 1;
        }
    }
    if (puts("]}") == EOF || fflush(stdout) != 0) { close(project_fd); close(base_fd); return 1; }
    if (close(project_fd) != 0) { close(base_fd); return 1; }
    if (close(base_fd) != 0) return 1;
    return 0;
}
