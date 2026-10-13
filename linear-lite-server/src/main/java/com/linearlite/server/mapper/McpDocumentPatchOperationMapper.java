package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.McpDocumentPatchOperation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface McpDocumentPatchOperationMapper extends BaseMapper<McpDocumentPatchOperation> {
    @Select("SELECT * FROM mcp_document_patch_operations "
            + "WHERE document_id = #{documentId} AND caller_id = #{callerId} AND operation_id = #{operationId} FOR UPDATE")
    McpDocumentPatchOperation selectForUpdate(
            @Param("documentId") Long documentId,
            @Param("callerId") Long callerId,
            @Param("operationId") String operationId);
}
