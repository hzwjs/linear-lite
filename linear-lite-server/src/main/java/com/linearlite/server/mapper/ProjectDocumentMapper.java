package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.dto.ProjectDocumentTreeNode;
import com.linearlite.server.entity.ProjectDocument;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ProjectDocumentMapper extends BaseMapper<ProjectDocument> {

    /** 文档树只读取结构字段，禁止通用实体查询把所有 LONGTEXT 正文加载到服务端。 */
    @Select("""
            <script>
            SELECT d.id,
                   d.project_id AS projectId,
                   d.parent_document_id AS parentDocumentId,
                   d.title,
                   d.sort_order AS sortOrder,
                   d.version,
                   favorite.id IS NOT NULL AS favorited,
                   d.updated_at AS updatedAt
            FROM project_documents d
            LEFT JOIN project_document_favorites favorite
              ON favorite.document_id = d.id AND favorite.user_id = #{userId}
            WHERE d.project_id = #{projectId}
            <choose>
                <when test="archived">
                    AND d.archived_at IS NOT NULL
                </when>
                <otherwise>
                    AND d.archived_at IS NULL
                </otherwise>
            </choose>
            ORDER BY d.parent_document_id ASC, d.sort_order ASC, d.id ASC
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "projectId", javaType = Long.class),
            @Arg(column = "parentDocumentId", javaType = Long.class),
            @Arg(column = "title", javaType = String.class),
            @Arg(column = "sortOrder", javaType = Integer.class),
            @Arg(column = "version", javaType = Long.class),
            @Arg(column = "favorited", javaType = boolean.class),
            @Arg(column = "updatedAt", javaType = java.time.LocalDateTime.class)
    })
    List<ProjectDocumentTreeNode> selectTreeNodes(
            @Param("projectId") Long projectId,
            @Param("userId") Long userId,
            @Param("archived") boolean archived);

    @Update("""
            UPDATE project_documents
            SET title = #{title}, content_json = #{contentJson}, last_editor_id = #{editorId},
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND version = #{expectedVersion} AND archived_at IS NULL
            """)
    int updateContentIfVersionMatches(
            @Param("id") Long id,
            @Param("expectedVersion") Long expectedVersion,
            @Param("title") String title,
            @Param("contentJson") String contentJson,
            @Param("editorId") Long editorId);

    @Update("""
            UPDATE project_documents
            SET parent_document_id = #{parentDocumentId}, sort_order = #{sortOrder},
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int updatePosition(
            @Param("id") Long id,
            @Param("parentDocumentId") Long parentDocumentId,
            @Param("sortOrder") Integer sortOrder);

    /** 数据库递归查询是循环校验和子树操作的唯一后代关系来源。 */
    @Select("""
            WITH RECURSIVE document_subtree AS (
                SELECT id FROM project_documents WHERE id = #{documentId} AND project_id = #{projectId}
                UNION ALL
                SELECT child.id FROM project_documents child
                INNER JOIN document_subtree parent ON child.parent_document_id = parent.id
                WHERE child.project_id = #{projectId}
            )
            SELECT id FROM document_subtree
            """)
    List<Long> selectSubtreeIds(@Param("projectId") Long projectId, @Param("documentId") Long documentId);

    /** 项目行是文档树写入的固定互斥点，即使项目尚无文档也能串行化首次创建。 */
    @Select("SELECT id FROM projects WHERE id = #{projectId} FOR UPDATE")
    Long lockProjectDocumentMutations(@Param("projectId") Long projectId);
}
