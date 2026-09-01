package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rpa.server.common.ApiException;
import com.rpa.server.entity.Device;
import com.rpa.server.entity.DeviceGroup;
import com.rpa.server.entity.DeviceGroupMember;
import com.rpa.server.mapper.DeviceGroupMapper;
import com.rpa.server.mapper.DeviceGroupMemberMapper;
import com.rpa.server.mapper.DeviceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DeviceGroupService {
    private final DeviceGroupMapper groupMapper;
    private final DeviceGroupMemberMapper memberMapper;
    private final DeviceMapper deviceMapper;

    public DeviceGroupService(DeviceGroupMapper groupMapper,
                              DeviceGroupMemberMapper memberMapper,
                              DeviceMapper deviceMapper) {
        this.groupMapper = groupMapper;
        this.memberMapper = memberMapper;
        this.deviceMapper = deviceMapper;
    }

    public List<Map<String, Object>> list() {
        List<DeviceGroup> groups = groupMapper.selectList(
                new QueryWrapper<DeviceGroup>().orderByDesc("id"));
        return groups.stream().map(g -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", g.id);
            m.put("name", g.name);
            m.put("remark", g.remark);
            m.put("createdAt", g.createdAt);
            m.put("deviceCount", memberMapper.selectCount(
                    new QueryWrapper<DeviceGroupMember>().eq("group_id", g.id)));
            return m;
        }).toList();
    }

    public DeviceGroup create(String name, String remark) {
        if (name == null || name.isBlank()) throw new ApiException("分组名不能为空");
        DeviceGroup g = new DeviceGroup();
        g.name = name;
        g.remark = remark;
        groupMapper.insert(g);
        return g;
    }

    public void update(long id, String name, String remark) {
        require(id);
        DeviceGroup upd = new DeviceGroup();
        upd.id = id;
        upd.name = name;
        upd.remark = remark;
        groupMapper.updateById(upd);
    }

    @Transactional
    public void delete(long id) {
        require(id);
        groupMapper.deleteById(id);
        memberMapper.delete(new QueryWrapper<DeviceGroupMember>().eq("group_id", id));
        Device upd = new Device();
        upd.groupId = 0L;
        deviceMapper.update(upd, new QueryWrapper<Device>().eq("group_id", id));
    }

    public void setMembers(long groupId, List<Long> deviceIds) {
        require(groupId);
        memberMapper.delete(new QueryWrapper<DeviceGroupMember>().eq("group_id", groupId));
        if (deviceIds != null) {
            for (Long deviceId : deviceIds) {
                DeviceGroupMember m = new DeviceGroupMember();
                m.groupId = groupId;
                m.deviceId = deviceId;
                memberMapper.insert(m);
                Device upd = new Device();
                upd.id = deviceId;
                upd.groupId = groupId;
                deviceMapper.updateById(upd);
            }
        }
    }

    public List<Device> devicesOf(long groupId) {
        List<Long> ids = memberMapper.selectList(
                        new QueryWrapper<DeviceGroupMember>().eq("group_id", groupId))
                .stream().map(m -> m.deviceId).toList();
        if (ids.isEmpty()) return List.of();
        List<Device> devices = deviceMapper.selectBatchIds(ids);
        devices.forEach(d -> d.secret = null);
        return devices;
    }

    private DeviceGroup require(long id) {
        DeviceGroup g = groupMapper.selectById(id);
        if (g == null) throw new ApiException(404, "分组不存在");
        return g;
    }
}
