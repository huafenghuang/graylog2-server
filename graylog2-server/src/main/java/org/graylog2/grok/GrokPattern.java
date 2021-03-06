/**
 * This file is part of Graylog.
 *
 * Graylog is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Graylog is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Graylog.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.graylog2.grok;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import org.bson.types.ObjectId;

import javax.persistence.Id;

@JsonAutoDetect
public class GrokPattern {

    @Id
    @org.mongojack.ObjectId
    public ObjectId id;
    
    public String name;
    
    public String pattern;

    @Override
    public String toString() {
        return "GrokPattern{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", pattern='" + pattern + '\'' +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        GrokPattern that = (GrokPattern) o;

        if (!name.equals(that.name)) return false;
        if (!pattern.equals(that.pattern)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + pattern.hashCode();
        return result;
    }
}
