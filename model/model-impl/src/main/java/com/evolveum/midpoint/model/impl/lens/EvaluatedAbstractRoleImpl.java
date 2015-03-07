/**
 * Copyright (c) 2015 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.evolveum.midpoint.model.impl.lens;

import com.evolveum.midpoint.model.api.context.EvaluatedAbstractRole;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.util.DebugUtil;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractRoleType;

/**
 * @author semancik
 *
 */
public class EvaluatedAbstractRoleImpl implements EvaluatedAbstractRole {
	
	PrismObject<? extends AbstractRoleType> role;
	
	@Override
	public PrismObject<? extends AbstractRoleType> getRole() {
		return role;
	}

	public void setRole(PrismObject<? extends AbstractRoleType> role) {
		this.role = role;
	}

	@Override
	public String debugDump() {
		return debugDump(0);
	}

	@Override
	public String debugDump(int indent) {
		StringBuilder sb = new StringBuilder();
		DebugUtil.debugDumpLabel(sb, "EvaluatedAbstractRole", indent);
		sb.append("\n");
		DebugUtil.debugDumpWithLabel(sb, "Role", role, indent + 1);
		return sb.toString();
	}
	

}